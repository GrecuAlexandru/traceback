package dev.traceback.indexing;

import dev.traceback.ingestion.*;
import dev.traceback.knowledge.*;
import dev.traceback.search.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Default: deterministic application/SQL gate. Opt in to the installed model with
 * -Dtraceback.evaluation.live-embeddings=true; never downloads a model. */
@Testcontainers
@SpringBootTest(properties={"traceback.indexing.enabled=true","traceback.embedding.dimensions=768",
        "traceback.embedding.digest=sha256:0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f",
        "traceback.ingestion.scheduling-enabled=false"})
class M15RetrievalEvaluationTest {
    static final String ROOT=TestDocumentStorage.directory();
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);r.add("traceback.documents.storage-directory",()->ROOT);
    }
    @MockitoBean JwtDecoder decoder;
    @MockitoBean EmbeddingProvider embeddings;
    @Autowired JdbcTemplate jdbc; @Autowired DocumentService documents; @Autowired IngestionRepository jobs;
    @Autowired IngestionWorker verification; @Autowired IndexWorker indexing; @Autowired SearchService search; @Autowired IndexSettings settings;
    record Document(String key,String title,String text,String topic,String variant) {}
    record Query(String key,String q,String kind,String split,Map<String,Integer> relevance) {}
    record Dataset(int version,String unit,String labelPolicy,List<Document> documents,List<Query> queries) {}
    Jwt token(String subject) {return Jwt.withTokenValue("synthetic").header("alg","RS256").issuer("https://evaluation.invalid").subject(subject).claim("traceback_principal_type","human").build();}
    UUID organization(String subject) {
        UUID org=UUID.randomUUID(),user=UUID.randomUUID();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?,?)",org,"Synthetic "+subject);
        jdbc.update("INSERT INTO app_users(id,issuer,subject) VALUES (?,'https://evaluation.invalid',?)",user,subject);
        jdbc.update("INSERT INTO organization_memberships(id,organization_id,user_id,role) VALUES (?,?,?,'ADMIN')",UUID.randomUUID(),org,user);
        return org;
    }
    UUID index(UUID org,String user,Document d) {
        var doc=documents.upload(token(user),org,d.title(),new MockMultipartFile("file","runbook.md","text/markdown",d.text().getBytes(StandardCharsets.UTF_8)));
        var event=jobs.events().stream().filter(e->e.indexGeneration()==null && e.message().revision().equals(doc.currentRevision().id())).findFirst().orElseThrow();
        assertTrue(verification.handle(event.body()).acknowledge());
        var next=jobs.events().stream().filter(e->e.indexGeneration()!=null && e.message().revision().equals(doc.currentRevision().id())).findFirst().orElseThrow();
        assertTrue(indexing.handle(next.body(),null).acknowledge(),"Indexing must succeed, including live inference when selected");
        return doc.id();
    }
    static float[] fixtureVector(String value) {
        float[] vector=new float[768];vector[0]=0.01f;
        for(String word:value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+"))
            if(!word.isBlank())vector[Math.floorMod(word.hashCode(),vector.length)]++;
        return vector;
    }
    static double percentile(List<Double> values,double percentile) {
        var sorted=values.stream().sorted().toList();return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*percentile)-1));
    }
    @Test void evaluateFrozenGradedCorpusWithoutTuningParameters() throws Exception {
        boolean live=Boolean.getBoolean("traceback.evaluation.live-embeddings");
        EmbeddingProvider provider=live?new OllamaEmbeddingProvider(settings,"http://127.0.0.1:11435",20000):null;
        when(embeddings.documents(anyList())).thenAnswer(c->{List<String> texts=c.getArgument(0);return live?provider.documents(texts):texts.stream().map(M15RetrievalEvaluationTest::fixtureVector).toList();});
        when(embeddings.query(anyString())).thenAnswer(c->live?provider.query(c.getArgument(0)):fixtureVector(c.getArgument(0)));
        var json=JsonMapper.builder().build();byte[] input=Files.readAllBytes(Path.of("../evaluations/m15/retrieval-dataset.json"));
        var dataset=json.readValue(input,Dataset.class);
        assertTrue(dataset.documents().size()>=30);assertTrue(dataset.queries().size()>=75);
        assertEquals(dataset.documents().size(),dataset.documents().stream().map(Document::key).distinct().count());
        Set<String> keys=new HashSet<>(dataset.documents().stream().map(Document::key).toList());
        for(var q:dataset.queries()) {assertFalse(q.relevance().isEmpty());assertTrue(keys.containsAll(q.relevance().keySet()));assertTrue(q.relevance().values().stream().allMatch(g->g>=1&&g<=3));}
        UUID org=organization("alice"),foreign=organization("bob");var names=new HashMap<UUID,String>();
        long seed=System.nanoTime();
        for(var d:dataset.documents()) {names.put(index(org,"alice",d),d.key());index(foreign,"bob",d);}
        long indexMs=(System.nanoTime()-seed)/1000000;
        var rows=new ArrayList<Map<String,Object>>();int leakage=0;
        for(var mode:SearchService.Mode.values()) {
            search.search(token("alice"),org,"HTTP 503",10,mode); // Explicit warm-up, excluded from observations.
            for(var q:dataset.queries()) {
                long start=System.nanoTime();var response=search.search(token("alice"),org,q.q(),10,mode);
                double latency=(System.nanoTime()-start)/1000000.0;
                assertNull(response.degradationCode(),"Benchmark must not silently degrade");
                var ranked=new ArrayList<String>();
                for(var result:response.results()) {
                    String name=names.get(result.source().documentId());
                    if(name==null) {leakage++;name="FOREIGN";}
                    if(!ranked.contains(name))ranked.add(name);
                }
                double r5=0,r10=0,mrr=0,dcg=0;
                for(int i=0;i<ranked.size();i++) {
                    int grade=q.relevance().getOrDefault(ranked.get(i),0);
                    if(grade>0) {r10+=1.0/q.relevance().size();if(i<5)r5+=1.0/q.relevance().size();if(mrr==0)mrr=1.0/(i+1);}
                    dcg+=(Math.pow(2,grade)-1)/(Math.log(i+2)/Math.log(2));
                }
                var ideal=q.relevance().values().stream().sorted(Comparator.reverseOrder()).limit(10).toList();double idcg=0;
                for(int i=0;i<ideal.size();i++)idcg+=(Math.pow(2,ideal.get(i))-1)/(Math.log(i+2)/Math.log(2));
                var row=new LinkedHashMap<String,Object>();row.put("key",q.key());row.put("kind",q.kind());row.put("split",q.split());row.put("mode",mode.name());
                row.put("rankedDocuments",ranked);row.put("recallAt5",r5);row.put("recallAt10",r10);row.put("mrrAt10",mrr);row.put("ndcgAt10",dcg/idcg);row.put("latencyMs",latency);rows.add(row);
            }
        }
        assertEquals(0,leakage,"Cross-tenant contamination is a correctness failure");
        var metrics=new LinkedHashMap<String,Object>();
        for(String split:List.of("all","development","evaluation"))for(var mode:SearchService.Mode.values()) {
            var subset=rows.stream().filter(r->r.get("mode").equals(mode.name())&&(split.equals("all")||r.get("split").equals(split))).toList();
            var result=new LinkedHashMap<String,Object>();result.put("queries",subset.size());
            for(String metric:List.of("recallAt5","recallAt10","mrrAt10","ndcgAt10"))result.put(metric,subset.stream().mapToDouble(r->((Number)r.get(metric)).doubleValue()).average().orElseThrow());
            var latencies=subset.stream().map(r->((Number)r.get("latencyMs")).doubleValue()).toList();
            result.put("p50Ms",percentile(latencies,.5));result.put("p95Ms",percentile(latencies,.95));
            result.put("queriesWithNoRelevantResult",subset.stream().filter(r->((Number)r.get("recallAt10")).doubleValue()==0).count());metrics.put(split+"/"+mode,result);
        }
        var report=new LinkedHashMap<String,Object>();report.put("evaluatedAtUTC",java.time.Instant.now().toString());
        report.put("datasetSha256",DocumentUploadValidator.checksum(input));report.put("embeddingKind",live?"real local nomic v1.5":"deterministic lexical-hash fixture; NOT semantic model quality");
        report.put("model",live?settings.model():"fixture-hash-768-v1");report.put("digest",live?settings.digest():"not-a-model");report.put("configuration",settings.configuration());
        report.put("documentsPerTenant",names.size());report.put("chunksPerTenant",jdbc.queryForObject("SELECT count(*) FROM document_chunks WHERE organization_id=?",Integer.class,org));
        report.put("queries",dataset.queries().size());report.put("seedAndIndexBothTenantsMs",indexMs);report.put("foreignResults",leakage);report.put("metrics",metrics);report.put("observations",rows);
        report.put("limitations",List.of("Synthetic English document-level labels; no production accuracy claim","Frozen parameters, no tuning on evaluation split","Latency includes authorized service and query embeddings; sequential warm host, not a load test","Default fixture vectors verify application ranking/SQL only; real model requires explicit opt-in"));
        Path output=Path.of(System.getProperty("traceback.evaluation.output-dir","target/m15"));Files.createDirectories(output);
        Files.writeString(output.resolve(live?"retrieval-live.json":"retrieval-deterministic.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
    }
}
