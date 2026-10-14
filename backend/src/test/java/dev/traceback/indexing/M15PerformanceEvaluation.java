package dev.traceback.indexing;

import dev.traceback.extraction.SourceBlock;
import dev.traceback.incident.*;
import dev.traceback.incident.dto.CreateIncidentRequest;
import dev.traceback.rag.*;
import dev.traceback.search.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in disposable benchmark, never a default CI latency threshold.
 * Inherits the frozen-corpus gate; all provider vectors here are deterministic fixtures. */
class M15PerformanceEvaluation extends M15RetrievalEvaluationTest {
    @Autowired IncidentService incidents; @Autowired IndexRepository indexes; @Autowired StructureChunker chunker;
    @Autowired ConversationService conversations; @Autowired ChatService chat;
    @MockitoBean ChatProvider chatProvider;

    @Test void benchmarkRepresentativeLocalDataPathsAndNearLimitPublication() throws Exception {
        when(embeddings.documents(anyList())).thenAnswer(c->((List<String>)c.getArgument(0)).stream().map(M15RetrievalEvaluationTest::fixtureVector).toList());
        when(embeddings.query(anyString())).thenAnswer(c->fixtureVector(c.getArgument(0)));
        when(chatProvider.generate(anyString(),anyString())).thenReturn(new ChatProvider.Result(
                "{\"status\":\"ANSWERED\",\"answer\":\"Synthetic performance observation\",\"references\":[\"S1\"]}",null,null));
        UUID org=organization("performance");var timings=new LinkedHashMap<String,Object>();
        UUID incident=null;
        for(int i=0;i<100;i++)incident=incidents.createIncident(token("performance"),org,new CreateIncidentRequest("Synthetic "+i,"HTTP 503 bounded benchmark description")).id();
        UUID selected=incident;
        measure(timings,"incidentRead100Rows",()->incidents.getIncident(token("performance"),org,selected));
        measure(timings,"incidentList100Rows",()->incidents.getIncidents(token("performance"),org,null,0,20));
        int count=0;UUID document=null;var throughput=new ArrayList<Map<String,Object>>();
        for(int target:List.of(50,200,500)) {
            long start=System.nanoTime();int added=target-count;
            while(count<target) {
                document=index(org,"performance",new Document("perf-"+count,"Synthetic runbook "+count,
                        "# Recovery\n\nHTTP 503 connection timeout. Synthetic job "+count+" uses bounded retries and token-fenced publication. "+"diagnostic observation ".repeat(10),"performance","current"));count++;
            }
            double seconds=(System.nanoTime()-start)/1e9;
            throughput.add(Map.of("corpusDocuments",target,"addedDocuments",added,"seconds",seconds,"documentsPerSecond",added/seconds));
            jdbc.execute("ANALYZE");
            for(var mode:SearchService.Mode.values())measure(timings,"search/"+target+"/"+mode,
                    ()->search.search(token("performance"),org,"HTTP 503 connection timeout",10,mode));
        }
        UUID doc=document;
        measure(timings,"documentMetadataList500Rows",()->documents.list(token("performance"),org,null,0,20));
        measure(timings,"documentDownload",()->documents.download(token("performance"),org,doc,null));
        measure(timings,"conversationCreate",()->conversations.create(token("performance"),org,"Synthetic benchmark"));
        UUID conversation=conversations.create(token("performance"),org,"Turn persistence benchmark").id();
        measure(timings,"ragEndToEndScriptedProvider",()->chat.submit(token("performance"),org,conversation,UUID.randomUUID(),"HTTP 503"));
        var publication=new ArrayList<Map<String,Object>>();
        for(int chunks:List.of(64,256,512))for(int trial=0;trial<5;trial++) {
            var d=documents.upload(token("performance"),org,"Publication "+chunks,
                    new org.springframework.mock.web.MockMultipartFile("file","benchmark.txt","text/plain","Synthetic publication fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var verify=jobs.events().stream().filter(e->e.indexGeneration()==null&&e.message().revision().equals(d.currentRevision().id())).findFirst().orElseThrow();
            assertTrue(verification.handle(verify.body()).acknowledge());
            var event=jobs.events().stream().filter(e->e.indexGeneration()!=null&&e.message().revision().equals(d.currentRevision().id())).findFirst().orElseThrow();
            var job=indexes.claim(IndexMessage.parse(event.body())).job();
            var blocks=new ArrayList<SourceBlock>();
            for(int i=0;i<chunks;i++)blocks.add(new SourceBlock("Publication diagnostic "+i+" "+"x".repeat(1400),"Section "+i,null,i*1500,i*1500+1428));
            var batch=chunker.chunks(job.revision(),blocks);assertEquals(chunks,batch.size());
            var vectors=batch.stream().map(c->{float[] v=new float[768];for(int i=0;i<v.length;i++)v[i]=(float)(.01+.05*Math.sin(i+c.ordinal()*.13));return v;}).toList();
            long start=System.nanoTime();assertTrue(indexes.publish(job,batch,vectors,0));double ms=(System.nanoTime()-start)/1e6;
            assertEquals(chunks,jdbc.queryForObject("SELECT count(*) FROM chunk_embeddings WHERE generation_id=?",Integer.class,job.id()));
            publication.add(Map.of("chunks",chunks,"dimensions",768,"trial",trial,"publicationMs",ms));
        }
        jdbc.execute("ANALYZE");
        // Representative eligibility plan; actual ranked service latency is measured above.
        var plans=jdbc.queryForList("""
            EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON)
            SELECT c.id FROM document_chunks c
            JOIN document_index_heads h ON h.organization_id=c.organization_id AND h.document_id=c.document_id
              AND h.revision_id=c.revision_id AND h.generation_id=c.generation_id AND h.configuration_id=c.configuration_id
            JOIN documents d ON d.organization_id=c.organization_id AND d.id=c.document_id
            JOIN document_revisions r ON r.id=c.revision_id AND r.document_id=c.document_id AND r.organization_id=c.organization_id
            JOIN index_generations g ON g.id=c.generation_id AND g.organization_id=c.organization_id
            WHERE c.organization_id=? AND d.status='AVAILABLE' AND r.status='AVAILABLE'
              AND d.current_revision_id=c.revision_id AND g.status='INDEXED'
              AND EXISTS(SELECT 1 FROM organization_memberships m JOIN app_users u ON u.id=m.user_id
                WHERE m.organization_id=c.organization_id AND u.issuer='https://evaluation.invalid' AND u.subject='performance')
              AND c.terms @@ plainto_tsquery('simple','HTTP 503')
            ORDER BY c.generation_id,c.id LIMIT 40
            """,org);
        var report=new LinkedHashMap<String,Object>();report.put("evaluatedAtUTC",java.time.Instant.now().toString());report.put("timings",timings);
        report.put("pipelineThroughput",throughput);report.put("publication",publication);report.put("keywordEligibilityPlan",plans);
        report.put("environment",Map.of("java",System.getProperty("java.version"),"os",System.getProperty("os.name"),"postgres",jdbc.queryForObject("SHOW server_version",String.class),"pgvector",jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'",String.class)));
        report.put("limitations",List.of("Warm sequential local Testcontainers; not concurrent load or production throughput","Pipeline uses fixture vectors and TXT/Markdown, excludes real inference and PDF cost","Publication directly constructs near-limit derived blocks; source bytes are synthetic fixture metadata","Five publication samples per size; no unsupported pass/fail latency threshold","Plan is representative keyword eligibility SQL, not an exact ranked search-plan capture"));
        Path output=Path.of(System.getProperty("traceback.evaluation.output-dir","target/m15"));Files.createDirectories(output);
        Files.writeString(output.resolve("performance.json"),JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
    }
    private void measure(Map<String,Object> timings,String key,Runnable operation) {
        for(int i=0;i<3;i++)operation.run();var samples=new ArrayList<Double>();
        for(int i=0;i<20;i++){long start=System.nanoTime();operation.run();samples.add((System.nanoTime()-start)/1e6);}
        timings.put(key,Map.of("samples",samples,"p50Ms",percentile(samples,.5),"p95Ms",percentile(samples,.95)));
    }
}
