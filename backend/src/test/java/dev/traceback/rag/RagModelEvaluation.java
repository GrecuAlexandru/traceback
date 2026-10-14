package dev.traceback.rag;

import dev.traceback.indexing.*;
import dev.traceback.ingestion.*;
import dev.traceback.knowledge.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.anyString;

/** Explicit opt-in real local chat + real M12 embeddings; synthetic disposable data only. */
@Testcontainers
@SpringBootTest(properties={"traceback.chat.enabled=true","traceback.indexing.enabled=true",
    "traceback.embedding.digest=sha256:0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f",
    "traceback.embedding.dimensions=768","traceback.ingestion.scheduling-enabled=false"})
class RagModelEvaluation {
    static final String ROOT=TestDocumentStorage.directory();
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("traceback.documents.storage-directory",()->ROOT);}
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean OllamaChatProvider provider;
    @Autowired JdbcTemplate jdbc;@Autowired DocumentService documents;@Autowired IngestionRepository jobs;@Autowired IngestionWorker verification;
    @Autowired IndexWorker indexing;@Autowired ChatService chat;@Autowired ConversationService conversations;@Autowired ChatSettings settings;@Autowired IndexSettings embeddingSettings;
    record Document(String key,String title,String text) {}
    record Query(String key,String kind,String question,String priorQuestion,List<String> expectedSources,List<String> expectedTerms,String expectedStatus) {}
    record Dataset(int version,List<Document> documents,List<Query> queries) {}
    Jwt token(String name) {return Jwt.withTokenValue("synthetic").header("alg","RS256").issuer("https://issuer.example").subject(name).claim("traceback_principal_type","human").build();}
    UUID organization(String name,String subject) {UUID org=UUID.randomUUID(),user=UUID.randomUUID();jdbc.update("INSERT INTO organizations(id,name) VALUES (?,?)",org,name);jdbc.update("INSERT INTO app_users(id,issuer,subject) VALUES (?,'https://issuer.example',?)",user,subject);jdbc.update("INSERT INTO organization_memberships(id,organization_id,user_id,role) VALUES (?,?,?,'ADMIN')",UUID.randomUUID(),org,user);return org;}
    UUID index(UUID org,String subject,Document d) {
        var doc=documents.upload(token(subject),org,d.title(),new MockMultipartFile("file","runbook.md","text/markdown",d.text().getBytes(StandardCharsets.UTF_8)));
        var event=jobs.events().stream().filter(e->e.indexGeneration()==null && e.message().revision().equals(doc.currentRevision().id())).findFirst().orElseThrow();assertTrue(verification.handle(event.body()).acknowledge());
        var generation=jobs.events().stream().filter(e->e.indexGeneration()!=null && e.message().revision().equals(doc.currentRevision().id())).findFirst().orElseThrow();assertTrue(indexing.handle(generation.body(),null).acknowledge());return doc.id();
    }
    @Test void recordRealModelObservationsWithoutInventingGroundedness() throws Exception {
        var mapper=JsonMapper.builder().build();Path root=Path.of("../evaluations/m13");byte[] bytes=Files.readAllBytes(root.resolve("dataset.json"));var dataset=mapper.readValue(bytes,Dataset.class);
        UUID org=organization("Evaluation A","alice"),foreign=organization("Evaluation B","bob");var names=new HashMap<UUID,String>();
        for(var d:dataset.documents())names.put(index(org,"alice",d),d.key());index(foreign,"bob",new Document("foreign","Private foreign procedure","FOREIGN_SENTINEL's secret recovery code is FOREIGN_CODE_8842."));
        var rawOutputs=new ArrayList<String>();var suppliedCalls=new ArrayList<List<String>>();
        var titleKeys=new HashMap<String,String>();for(var d:dataset.documents())titleKeys.put(d.title(),d.key());
        Mockito.doAnswer(call->{
            @SuppressWarnings("unchecked") var payload=(Map<String,Object>)mapper.readValue((String)call.getArgument(1),Map.class);
            @SuppressWarnings("unchecked") var evidence=(List<Map<String,Object>>)payload.get("evidence");
            suppliedCalls.add(evidence.stream().map(e->titleKeys.getOrDefault(e.get("title"),"FOREIGN")).toList());
            var result=(ChatProvider.Result)call.callRealMethod();rawOutputs.add(result.content());return result;
        }).when(provider).generate(anyString(),anyString());
        var observations=new ArrayList<Map<String,Object>>();int answered=0,failed=0,leaks=0,expectedStatus=0,abstentions=0,termMatches=0;double recall=0;
        String selectedCase=System.getProperty("traceback.evaluation.case");
        var queries=dataset.queries().stream().filter(q->selectedCase==null || q.key().equals(selectedCase)).toList();
        int termCases=(int)queries.stream().filter(q->!q.expectedTerms().isEmpty()).count();
        int abstentionCases=(int)queries.stream().filter(q->q.expectedStatus().equals("INSUFFICIENT_EVIDENCE")).count();
        int retrievalCases=(int)queries.stream().filter(q->!q.expectedSources().isEmpty()).count();
        for(var query:queries) {
            UUID conversation=conversations.create(token("alice"),org,"Evaluation "+query.key()).id();var row=new LinkedHashMap<String,Object>();row.put("key",query.key());row.put("kind",query.kind());row.put("question",query.question());
            int callsBefore=suppliedCalls.size();long start=System.nanoTime();
            try {
                if(query.priorQuestion()!=null)chat.submit(token("alice"),org,conversation,UUID.randomUUID(),query.priorQuestion());
                var response=chat.submit(token("alice"),org,conversation,UUID.randomUUID(),query.question());row.put("status",response.status());row.put("answer",response.answer());
                row.put("citations",response.citations().stream().map(c->Map.of("reference",c.reference(),"source",names.getOrDefault(c.documentId(),"FOREIGN"),"excerpt",c.excerpt())).toList());
                if(response.status().equals("ANSWERED"))answered++;
                if(response.status().equals(query.expectedStatus()))expectedStatus++;
                if(query.expectedStatus().equals("INSUFFICIENT_EVIDENCE")) {if(response.status().equals("INSUFFICIENT_EVIDENCE"))abstentions++;}
                if(!query.expectedTerms().isEmpty()) {boolean matches=query.expectedTerms().stream().allMatch(v->response.answer()!=null && response.answer().toLowerCase(Locale.ROOT).contains(v));row.put("expectedTermMatch",matches);if(matches)termMatches++;}
                for(var citation:response.citations())if(!names.containsKey(citation.documentId()))leaks++;
                if(response.answer()!=null && response.answer().contains("FOREIGN_CODE_8842"))leaks++;
            } catch(ChatFailure failure) {failed++;row.put("status","FAILED");row.put("errorCode",failure.code());}
            var supplied=suppliedCalls.size()>callsBefore?suppliedCalls.getLast():List.<String>of();row.put("suppliedSources",supplied);
            if(!query.expectedSources().isEmpty()) {double r=(double)query.expectedSources().stream().filter(supplied::contains).count()/query.expectedSources().size();recall+=r;row.put("suppliedEvidenceRecall",r);}
            row.put("latencyMs",(System.nanoTime()-start)/1000000);observations.add(row);
            System.out.println("M13 synthetic evaluation: "+query.key()+" "+row.get("status")+" "+row.get("latencyMs")+"ms "+row.getOrDefault("errorCode",""));
        }
        assertEquals(0,leaks);var report=new LinkedHashMap<String,Object>();report.put("date",java.time.LocalDate.now(java.time.ZoneId.of("Europe/Bucharest")).toString());report.put("evaluatedAtUTC",java.time.Instant.now().toString());report.put("datasetSha256",DocumentUploadValidator.checksum(bytes));
        report.put("protocol","rag-json-v2");report.put("schema","answered-nonempty-references|abstention-empty-v1");report.put("promptSha256",DocumentUploadValidator.checksum(RagContext.SYSTEM.getBytes(StandardCharsets.UTF_8)));report.put("provider","real local Ollama / Spring AI 2.0.1");report.put("chatConfiguration",settings);report.put("embeddingModel",embeddingSettings.model());report.put("embeddingDigest",embeddingSettings.digest());report.put("queries",queries.size());
        report.put("metrics",Map.of("successfulAnsweredTurns",answered,"failedCases",failed,"foreignLeaks",leaks,"expectedStatusMatches",expectedStatus,
            "insufficientEvidence",abstentions+"/"+abstentionCases,"expectedTermMatches",termMatches+"/"+termCases,"suppliedEvidenceRecall",retrievalCases==0?0:recall/retrievalCases));
        report.put("rawSyntheticModelOutputs",rawOutputs);
        report.put("citationValidity","Successful answers passed deterministic backend reference and source visibility validation; failures recorded separately");
        report.put("semanticClaimSupport","Requires manual review of saved answers/excerpts; expected term matching is not semantic groundedness");report.put("observations",observations);
        report.put("limitations",List.of("Ten synthetic queries; no production accuracy claim","Mocked JWT decoder, real database authorization","Generation failures included, never counted as grounded answers","Prompt injection authority remains enforced by code; model obedience is not guaranteed","Deletion/supersession/revocation exercised by separate deterministic integration tests"));
        Files.writeString(root.resolve(selectedCase==null?"results.json":"diagnostic-results.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
        assertTrue(answered>0,"Real model did not produce any valid cited answers; report records unresolved failures");
    }
}
