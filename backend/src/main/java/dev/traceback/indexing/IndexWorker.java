package dev.traceback.indexing;

import dev.traceback.extraction.*;
import dev.traceback.ingestion.*;
import dev.traceback.knowledge.DocumentUploadValidator;
import dev.traceback.knowledge.storage.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class IndexWorker {
    private final IndexRepository jobs;private final DocumentStorage storage;private final DocumentExtractor extractor;
    private final StructureChunker chunker;private final EmbeddingProvider embeddings;private final IndexSettings settings;
    private final ObjectProvider<IngestionQueue> queue;private final MeterRegistry metrics;
    private final DocumentUploadValidator limits;
    public IndexWorker(IndexRepository jobs,DocumentStorage storage,DocumentExtractor extractor,StructureChunker chunker,
                       EmbeddingProvider embeddings,IndexSettings settings,ObjectProvider<IngestionQueue> queue,MeterRegistry metrics,DocumentUploadValidator limits) {
        this.jobs=jobs;this.storage=storage;this.extractor=extractor;this.chunker=chunker;this.embeddings=embeddings;
        this.settings=settings;this.queue=queue;this.metrics=metrics;this.limits=limits;
    }
    public IngestionWorker.Handling handle(String body,String receipt) {
        IndexMessage message;
        try {message=IndexMessage.parse(body);}catch(IllegalArgumentException ex){return new IngestionWorker.Handling(false,1);}
        var claim=jobs.claim(message);
        if(claim.outcome().equals("ACK"))return new IngestionWorker.Handling(true,0);
        if(!claim.outcome().equals("CLAIMED"))return new IngestionWorker.Handling(false,claim.outcome().equals("BUSY")?settings.leaseSeconds():1);
        var job=claim.job();long started=System.nanoTime();var lost=new AtomicBoolean();Thread owner=Thread.currentThread();
        try(var heartbeat=Executors.newSingleThreadScheduledExecutor()) {
            heartbeat.scheduleAtFixedRate(()->{
                try {
                    if(!jobs.heartbeat(job))throw new IllegalStateException();
                    if(receipt!=null)queue.getObject().defer(receipt,settings.leaseSeconds()+30);
                }catch(RuntimeException ex){lost.set(true);owner.interrupt();}
            },settings.heartbeatSeconds(),settings.heartbeatSeconds(),TimeUnit.SECONDS);
            try {
                List<SourceBlock> blocks=jobs.artifact(job);
                if(blocks==null) {
                    byte[] bytes=storage.read(job.provider(),job.key(),Math.min(extractor.maximumBytes(),limits.maximumBytes()));
                    if(bytes.length!=job.size() || !DocumentUploadValidator.checksum(bytes).equals(job.checksum()))throw new ExtractionFailure("CONTENT_CORRUPT");
                    blocks=extractor.extract(bytes,job.media());
                    if(lost.get() || !jobs.extracted(job,blocks))return new IngestionWorker.Handling(false,settings.leaseSeconds());
                    metrics.counter("traceback.indexing.extractions","result","success").increment();
                }
                if(lost.get() || !jobs.indexing(job))return new IngestionWorker.Handling(false,settings.leaseSeconds());
                var chunks=chunker.chunks(job.revision(),blocks);var vectors=new ArrayList<float[]>();
                for(int i=0;i<chunks.size();i+=8) {
                    if(lost.get())return new IngestionWorker.Handling(false,settings.leaseSeconds());
                    var batch=embeddings.documents(chunks.subList(i,Math.min(i+8,chunks.size())).stream().map(StructureChunker.Chunk::text).toList());
                    if(batch.size()!=Math.min(8,chunks.size()-i))throw new EmbeddingFailure("EMBEDDING_BATCH");
                    vectors.addAll(batch);
                }
                if(lost.get())return new IngestionWorker.Handling(false,settings.leaseSeconds());
                boolean published;
                try {published=jobs.publish(job,chunks,vectors,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));}
                catch(RuntimeException ex) {metrics.counter("traceback.indexing.publication.failures").increment();throw ex;}
                if(published)metrics.counter("traceback.indexing.completed").increment();
                return new IngestionWorker.Handling(published,published?0:settings.leaseSeconds());
            } catch(ExtractionFailure ex) {
                boolean permanent=!Set.of("PARSER_UNAVAILABLE","PARSER_BUSY","EXTRACTION_INTERRUPTED").contains(ex.code());
                jobs.fail(job,ex.code(),permanent,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
                metrics.counter("traceback.indexing.extractions","result","failed").increment();
            } catch(EmbeddingFailure ex) {
                jobs.fail(job,ex.code(),!ex.code().equals("EMBEDDING_UNAVAILABLE"),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
                metrics.counter("traceback.indexing.embedding.failures").increment();
            } catch(StorageFailure ex) {jobs.fail(job,ex.code(),true,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));}
            catch(IOException ex) {jobs.fail(job,"STORAGE_UNAVAILABLE",false,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));}
            finally {heartbeat.shutdownNow();metrics.timer("traceback.indexing.duration").record(System.nanoTime()-started,TimeUnit.NANOSECONDS);}
        }
        // Publication/DB failures escape without acknowledgement. Fixed failures leave durable retry/DLQ state.
        if(jobs.find(job.id(),false).status().equals("RETRY"))metrics.counter("traceback.indexing.retries").increment();
        return new IngestionWorker.Handling(false,Math.min(60,1<<Math.min(job.attempts(),5)));
    }
}
