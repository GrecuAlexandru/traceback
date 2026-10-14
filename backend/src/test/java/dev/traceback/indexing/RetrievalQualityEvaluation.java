package dev.traceback.indexing;

import dev.traceback.ingestion.*;
import dev.traceback.knowledge.*;
import dev.traceback.search.*;
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

/** Explicit opt-in: -Dtest=RetrievalQualityEvaluation; never included in the deterministic default suite. */
@Testcontainers
@SpringBootTest(properties={"traceback.indexing.enabled=true","traceback.embedding.digest=sha256:0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f",
    "traceback.embedding.dimensions=768","traceback.ingestion.scheduling-enabled=false"})
class RetrievalQualityEvaluation {
    static final String ROOT=TestDocumentStorage.directory();
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("traceback.documents.storage-directory",()->ROOT);}
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;@Autowired DocumentService documents;@Autowired IngestionRepository jobs;@Autowired IngestionWorker verification;
    @Autowired IndexWorker indexing;@Autowired SearchService search;@Autowired IndexSettings settings;
    record Relevant(String document,String section){}
    record Query(String q,String kind,List<Relevant> relevant){}
    record Document(String key,String title,String text){}
    record Dataset(int version,List<Document> documents,List<Query> queries){}
    Jwt token(String subject){return Jwt.withTokenValue("synthetic").header("alg","RS256").issuer("https://issuer.example").subject(subject).claim("traceback_principal_type","human").build();}
    UUID organization(String name,String subject){UUID org=UUID.randomUUID(),user=UUID.randomUUID();jdbc.update("INSERT INTO organizations(id,name) VALUES (?,?)",org,name);jdbc.update("INSERT INTO app_users(id,issuer,subject) VALUES (?,'https://issuer.example',?)",user,subject);jdbc.update("INSERT INTO organization_memberships(id,organization_id,user_id,role) VALUES (?,?,?,'ADMIN')",UUID.randomUUID(),org,user);return org;}
    UUID index(UUID org,String subject,Document document){var doc=documents.upload(token(subject),org,document.title(),new MockMultipartFile("file","runbook.md","text/markdown",document.text().getBytes(StandardCharsets.UTF_8)));
        var verify=jobs.events().stream().filter(e->e.indexGeneration()==null && e.message().revision().equals(doc.currentRevision().id())).findFirst().orElseThrow();assertTrue(verification.handle(verify.body()).acknowledge());
        var event=jobs.events().stream().filter(e->e.indexGeneration()!=null && e.message().revision().equals(doc.currentRevision().id())).findFirst().orElseThrow();assertTrue(indexing.handle(event.body(),null).acknowledge(),"Real local model indexing failed");return doc.id();}
    @Test void evaluateRealProviderThroughPublishedTenantScopedIndex()throws Exception{
        var json=JsonMapper.builder().build();Path root=Path.of("../evaluations/m12");byte[] bytes=Files.readAllBytes(root.resolve("dataset.json"));var dataset=json.readValue(bytes,Dataset.class);
        UUID org=organization("Evaluation A","alice"),foreign=organization("Evaluation B","bob");var names=new HashMap<UUID,String>();
        for(var document:dataset.documents()){names.put(index(org,"alice",document),document.key());index(foreign,"bob",document);}
        assertTrue(jdbc.queryForObject("SELECT count(DISTINCT embedding::text)>1 FROM chunk_embeddings",Boolean.class));
        var metrics=new LinkedHashMap<String,Object>();var observations=new ArrayList<Map<String,Object>>();int leakage=0;
        for(var mode:SearchService.Mode.values()){
            double recall3=0,recall5=0,mrr5=0;
            for(var query:dataset.queries()){
                var response=search.search(token("alice"),org,query.q(),5,mode);assertNull(response.degradationCode(),"Real evaluation must not silently degrade");
                var results=response.results();var hits=new HashSet<Relevant>();int first=0;var labels=new ArrayList<Map<String,Object>>();
                for(int i=0;i<results.size();i++){
                    var c=results.get(i).source();if(!names.containsKey(c.documentId()))leakage++;
                    var label=new Relevant(names.getOrDefault(c.documentId(),"FOREIGN"),c.section());boolean relevant=query.relevant().contains(label);
                    if(relevant){hits.add(label);if(first==0)first=i+1;if(i<3)recall3+=1.0/query.relevant().size();}
                    labels.add(Map.of("document",label.document(),"section",label.section(),"rank",i+1,"relevant",relevant));
                }
                recall5+=(double)hits.size()/query.relevant().size();mrr5+=first==0?0:1.0/first;
                observations.add(Map.of("mode",mode.name(),"query",query.q(),"kind",query.kind(),"results",labels));
            }
            int n=dataset.queries().size();metrics.put(mode.name(),Map.of("recallAt3",recall3/n,"recallAt5",recall5/n,"mrrAt5",mrr5/n));
        }
        assertEquals(0,leakage);var report=new LinkedHashMap<String,Object>();report.put("date","2026-10-14");report.put("datasetSha256",DocumentUploadValidator.checksum(bytes));report.put("model",settings.model());report.put("modelDigest",settings.digest());report.put("configuration",settings.configuration());report.put("dimensions",settings.dimensions());
        report.put("pgvectorVersion",jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'",String.class));report.put("documentsPerTenant",names.size());report.put("chunksPerTenant",jdbc.queryForObject("SELECT count(*) FROM document_chunks WHERE organization_id=?",Integer.class,org));report.put("queries",dataset.queries().size());report.put("metrics",metrics);report.put("foreignResults",leakage);report.put("observations",observations);
        report.put("limitations",List.of("Small synthetic English corpus; no production relevance claim","One labeled relevant section per query","Real local inference; JWT decoder mocked for application authorization","No approximate vector index or reranker"));
        Files.writeString(root.resolve("results.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
    }
}
