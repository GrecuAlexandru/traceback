package dev.traceback.ingestion;

import dev.traceback.knowledge.DocumentUploadValidator;
import dev.traceback.knowledge.storage.DocumentStorage;
import dev.traceback.knowledge.storage.StorageFailure;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
public class IngestionWorker {
    private final IngestionRepository repository;
    private final DocumentStorage storage;
    private final DocumentUploadValidator limits;
    private final IngestionSettings settings;
    private final MeterRegistry metrics;
    public IngestionWorker(IngestionRepository repository,DocumentStorage storage,DocumentUploadValidator limits,
                           IngestionSettings settings,MeterRegistry metrics) {
        this.repository=repository;this.storage=storage;this.limits=limits;this.settings=settings;this.metrics=metrics;
    }
    public Handling handle(String body) {
        IngestionMessage message;
        try { message=IngestionMessage.parse(body); }
        catch (IllegalArgumentException ex) { metrics.counter("traceback.ingestion.invalid").increment();return new Handling(false,1); }
        var claim=repository.claim(message);
        return switch (claim.outcome()) {
            case "ACK","OBSOLETE" -> new Handling(true,0);
            case "INVALID","FAILED" -> new Handling(false,1);
            case "BUSY" -> new Handling(false,settings.visibilitySeconds());
            case "CLAIMED" -> process(claim.job());
            default -> throw new IllegalStateException("Unexpected claim outcome");
        };
    }
    private Handling process(IngestionRepository.Job job) {
        long started=System.nanoTime();String failure=null;boolean permanent=false;
        try {
            byte[] bytes=storage.read(job.provider(),job.key(),limits.maximumBytes());
            if (bytes.length!=job.size() || !DocumentUploadValidator.checksum(bytes).equals(job.checksum())) {
                failure="CONTENT_CORRUPT";permanent=true;
            }
        } catch (StorageFailure ex) { failure=ex.code();permanent=true; }
        catch (IOException | RuntimeException ex) { failure="STORAGE_UNAVAILABLE"; }
        long elapsed=System.nanoTime()-started;
        int backoff=settings.backoff(job.attempts());
        // No content, key, provider exception, filename, token or receipt handle enters diagnostics.
        boolean committed=repository.finish(job,failure,permanent,TimeUnit.NANOSECONDS.toMillis(elapsed),backoff);
        metrics.timer("traceback.ingestion.duration").record(elapsed,TimeUnit.NANOSECONDS);
        if (!committed) return new Handling(false,settings.visibilitySeconds());
        // Cancellation after a concurrent tombstone/supersession is durable handled work.
        var current=repository.find(job.organization(),job.document(),job.revision(),false);
        if (current.status().equals("VERIFIED") || current.status().equals("CANCELLED")) {
            metrics.counter("traceback.ingestion.completed","result",current.status()).increment();
            return new Handling(true,0);
        }
        metrics.counter("traceback.ingestion.failures","terminal",String.valueOf(current.status().equals("FAILED"))).increment();
        if (current.status().equals("RETRY")) metrics.counter("traceback.ingestion.retries").increment();
        return new Handling(false,current.status().equals("FAILED") ? 1 : backoff);
    }
    public record Handling(boolean acknowledge,int delaySeconds) {}
}
