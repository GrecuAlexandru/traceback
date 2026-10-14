package dev.traceback.indexing;

import dev.traceback.ingestion.*;
import dev.traceback.knowledge.*;
import dev.traceback.knowledge.dto.DocumentResponse;
import dev.traceback.knowledge.storage.DocumentStorage;
import dev.traceback.search.*;
import dev.traceback.security.HumanIdentityPolicy.Identity;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties={"traceback.indexing.enabled=true","traceback.embedding.digest=sha256:1111111111111111111111111111111111111111111111111111111111111111",
    "traceback.embedding.dimensions=3","traceback.indexing.chunk-size=200","traceback.indexing.overlap=20","traceback.ingestion.scheduling-enabled=false"})
@AutoConfigureMockMvc
class IndexIntegrationTest {
    static final String ROOT=TestDocumentStorage.directory();
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("traceback.documents.storage-directory",()->ROOT);}
    @MockitoBean JwtDecoder decoder;@MockitoBean EmbeddingProvider embeddings;@MockitoSpyBean DocumentStorage storage;
    @Autowired DocumentService documents;@Autowired IngestionWorker verification;@Autowired IngestionRepository ingestion;
    @Autowired IndexRepository indexes;@Autowired IndexWorker worker;@Autowired SearchService search;@Autowired SearchRepository retrieval;
    @Autowired StructureChunker chunker;@Autowired IndexSettings settings;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    UUID org,foreign;
    Jwt token(String user){return Jwt.withTokenValue("synthetic").header("alg","RS256").issuer("https://issuer.example").subject(user).claim("traceback_principal_type","human").build();}
    @BeforeEach void setup(){
        reset(embeddings,storage);
        for(String table:List.of("ingestion_outbox","document_index_heads","chunk_embeddings","document_chunks","extraction_artifacts","index_generations","ingestion_jobs"))jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE documents SET current_revision_id=NULL,status='DELETED'");
        for(String table:List.of("document_revisions","documents","organization_memberships","organizations","app_users"))jdbc.update("DELETE FROM "+table);
        org=org("A");foreign=org("B");member(org,"alice","ADMIN");member(org,"engineer","ENGINEER");member(org,"viewer","VIEWER");member(foreign,"bob","ADMIN");
        when(embeddings.documents(anyList())).thenAnswer(call->{List<String> texts=call.getArgument(0);return texts.stream().map(t->new float[]{1,0,0}).toList();});
        when(embeddings.query(anyString())).thenReturn(new float[]{1,0,0});
    }
    UUID org(String name){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO organizations(id,name) VALUES (?,?)",id,name);return id;}
    void member(UUID organization,String subject,String role){UUID user=UUID.randomUUID();jdbc.update("INSERT INTO app_users(id,issuer,subject) VALUES (?,'https://issuer.example',?)",user,subject);jdbc.update("INSERT INTO organization_memberships(id,organization_id,user_id,role) VALUES (?,?,?,?)",UUID.randomUUID(),organization,user,role);}
    MockMultipartFile text(String value){return new MockMultipartFile("file","runbook.md","text/markdown",value.getBytes(StandardCharsets.UTF_8));}
    DocumentResponse upload(UUID organization,String user,String value){return documents.upload(token(user),organization,"Runbook",text(value));}
    String prepare(DocumentResponse d){var e=ingestion.events().stream().filter(v->v.indexGeneration()==null && v.message().revision().equals(d.currentRevision().id())).findFirst().orElseThrow();assertTrue(verification.handle(e.body()).acknowledge());return event(d).body();}
    IngestionRepository.Event event(DocumentResponse d){return ingestion.events().stream().filter(v->v.indexGeneration()!=null && v.message().revision().equals(d.currentRevision().id())).reduce((a,b)->b).orElseThrow();}
    DocumentResponse indexed(String content){var doc=upload(org,"alice",content);assertTrue(worker.handle(prepare(doc),null).acknowledge());return doc;}
    List<ReciprocalRankFusion.Ranked> query(String q,SearchService.Mode mode){return search.search(token("alice"),org,q,10,mode).results();}
    String path(UUID organization){return "/api/v1/organizations/"+organization+"/search";}

    @Test void verifiedIsDistinctAndDuplicateIndexMessagesPublishOnce(){
        var doc=upload(org,"alice","# Database\n\nPostgreSQL connection timeout AUTH_TOKEN_EXPIRED π");String message=prepare(doc);
        assertEquals("VERIFIED",ingestion.find(org,doc.id(),doc.currentRevision().id(),false).status());assertTrue(query("PostgreSQL",SearchService.Mode.keyword).isEmpty());
        assertTrue(worker.handle(message,null).acknowledge());assertTrue(worker.handle(message,null).acknowledge());
        assertEquals(1,indexes.find(event(doc).indexGeneration(),false).attempts());assertEquals(1,query("AUTH_TOKEN_EXPIRED",SearchService.Mode.keyword).size());
        var source=query("connection timeout",SearchService.Mode.hybrid).getFirst().source();assertEquals(doc.id(),source.documentId());assertEquals(doc.currentRevision().id(),source.revisionId());assertEquals("Database",source.section());
    }
    @Test void crossTenantCorpusAndForeignIdsNeverEnterRankingOrCounts()throws Exception{
        var own=indexed("HTTP 503 technical recovery");var other=upload(foreign,"bob","HTTP 503 technical recovery foreign secret");assertTrue(worker.handle(prepare(other),null).acknowledge());
        for(var mode:SearchService.Mode.values()){var results=query("HTTP 503",mode);assertEquals(1,results.size());assertEquals(own.id(),results.getFirst().source().documentId());assertFalse(results.toString().contains("foreign secret"));}
        assertTrue(retrieval.keyword(foreign,new Identity("https://issuer.example","alice"),"HTTP 503",10).isEmpty());
        assertTrue(retrieval.semantic(foreign,new Identity("https://issuer.example","alice"),new float[]{1,0,0},settings.configuration(),10).isEmpty());
        mvc.perform(get("/api/v1/organizations/"+org+"/documents/"+other.id()+"/revisions/"+other.currentRevision().id()+"/indexing").with(jwt().jwt(t->t.issuer("https://issuer.example").subject("alice").claim("traceback_principal_type","human")))).andExpect(status().isNotFound());
    }
    @Test void apiRequiresAuthenticationAndMembershipForEveryMode()throws Exception{
        indexed("HTTP 503");for(String mode:List.of("keyword","semantic","hybrid")){
            mvc.perform(get(path(org)).param("q","HTTP 503").param("mode",mode)).andExpect(status().isUnauthorized());
            mvc.perform(get(path(foreign)).param("q","HTTP 503").param("mode",mode).with(jwt().jwt(t->t.issuer("https://issuer.example").subject("alice").claim("traceback_principal_type","human")))).andExpect(status().isForbidden());
            for(String subject:List.of("alice","engineer","viewer"))mvc.perform(get(path(org)).param("q","HTTP 503").param("mode",mode).with(jwt().jwt(t->t.issuer("https://issuer.example").subject(subject).claim("traceback_principal_type","human")))).andExpect(status().isOk()).andExpect(jsonPath("$.returnedCount").value(1));
        }
        mvc.perform(get(path(org)).param("q","HTTP").with(jwt().jwt(t->t.issuer("https://issuer.example").subject("alice").claim("role","ADMIN")))).andExpect(status().isForbidden());
    }
    @Test void membershipRevokedDuringEmbeddingIsRechecked(){indexed("HTTP 503");when(embeddings.query(anyString())).thenAnswer(c->{jdbc.update("DELETE FROM organization_memberships WHERE user_id=(SELECT id FROM app_users WHERE subject='alice')");return new float[]{1,0,0};});
        assertThrows(dev.traceback.organization.OrganizationPermissionDeniedException.class,()->query("HTTP",SearchService.Mode.hybrid));}
    @Test void deletionAndSupersessionDuringQueryEmbeddingCannotReturnOldSources() {
        var deleted=indexed("HTTP 503 source deleted during retrieval");
        when(embeddings.query(anyString())).thenAnswer(c->{documents.delete(token("alice"),org,deleted.id(),deleted.version());return new float[]{1,0,0};});
        assertTrue(query("HTTP 503",SearchService.Mode.hybrid).isEmpty());
        var replaced=indexed("HTTP 503 source superseded during retrieval");
        when(embeddings.query(anyString())).thenAnswer(c->{documents.revise(token("alice"),org,replaced.id(),replaced.version(),text("New unindexed source"));return new float[]{1,0,0};});
        assertTrue(query("HTTP 503",SearchService.Mode.semantic).isEmpty());
    }
    @Test void deletionAndSupersessionImmediatelyHideEveryMode(){
        var doc=indexed("NullPointerException original");documents.delete(token("alice"),org,doc.id(),doc.version());
        for(var mode:SearchService.Mode.values())assertTrue(query("NullPointerException",mode).isEmpty());
        var old=indexed("OutOfMemoryError superseded");var next=documents.revise(token("alice"),org,old.id(),old.version(),text("new current content"));
        for(var mode:SearchService.Mode.values())assertTrue(query("OutOfMemoryError",mode).isEmpty());
        assertTrue(worker.handle(prepare(next),null).acknowledge());assertEquals(next.currentRevision().id(),query("new current",SearchService.Mode.keyword).getFirst().source().revisionId());
    }
    @Test void providerOutageLeavesNoPartialIndexAndRecoveryReusesExtraction()throws Exception{
        var doc=upload(org,"alice","HTTP 503 "+"bounded paragraph ".repeat(110));String body=prepare(doc);
        when(embeddings.documents(anyList())).thenReturn(Collections.nCopies(8,new float[]{1,0,0})).thenThrow(new EmbeddingFailure("EMBEDDING_UNAVAILABLE"));
        assertFalse(worker.handle(body,null).acknowledge());assertEquals("RETRY",indexes.find(event(doc).indexGeneration(),false).status());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM document_chunks",Integer.class));assertTrue(query("HTTP",SearchService.Mode.keyword).isEmpty());
        doAnswer(c->{List<String> texts=c.getArgument(0);return texts.stream().map(t->new float[]{1,0,0}).toList();}).when(embeddings).documents(anyList());
        jdbc.update("UPDATE index_generations SET next_attempt_at=clock_timestamp()-interval '1 second'");reset(storage);assertTrue(worker.handle(body,null).acknowledge());
        verify(storage,never()).read(anyString(),any(),anyLong());assertFalse(query("HTTP",SearchService.Mode.keyword).isEmpty());
    }
    @Test void wrongDimensionsAndInvalidVectorsFailWithoutPublication(){
        var doc=upload(org,"alice","technical runbook");String body=prepare(doc);when(embeddings.documents(anyList())).thenReturn(List.of(new float[]{1,0}));
        assertFalse(worker.handle(body,null).acknowledge());assertEquals("FAILED",indexes.find(event(doc).indexGeneration(),false).status());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM document_index_heads",Integer.class));
        assertThrows(EmbeddingFailure.class,()->EmbeddingProvider.validate(new float[]{Float.NaN,0,0},3));assertThrows(EmbeddingFailure.class,()->EmbeddingProvider.validate(new float[]{0,0,0},3));
    }
    @Test void expiredLeaseCannotPublishAndCrashRecoveryFencesOldToken(){
        var doc=upload(org,"alice","HTTP 503");String body=prepare(doc);var message=IndexMessage.parse(body);var old=indexes.claim(message).job();
        assertEquals("BUSY",indexes.claim(message).outcome());jdbc.update("UPDATE index_generations SET lease_until=clock_timestamp()-interval '1 second'");assertFalse(indexes.heartbeat(old));
        var chunks=chunker.chunks(old.revision(),List.of(new dev.traceback.extraction.SourceBlock("HTTP 503",null,null,0,8)));
        assertFalse(indexes.publish(old,chunks,List.of(new float[]{1,0,0}),1));assertTrue(worker.handle(body,null).acknowledge());assertFalse(indexes.publish(old,chunks,List.of(new float[]{1,0,0}),1));assertEquals(2,indexes.find(old.id(),false).attempts());
    }
    @Test void concurrentClaimersHaveOneOwner()throws Exception{
        var doc=upload(org,"alice","HTTP 503");var message=IndexMessage.parse(prepare(doc));
        try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return indexes.claim(message).outcome();});var b=pool.submit(()->{gate.await();return indexes.claim(message).outcome();});gate.countDown();assertEquals(Set.of("CLAIMED","BUSY"),Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));}
    }
    @Test void deleteDuringEmbeddingAndDelayedMessagesCannotResurrect(){var doc=upload(org,"alice","HTTP 503");String body=prepare(doc);
        when(embeddings.documents(anyList())).thenAnswer(c->{documents.delete(token("alice"),org,doc.id(),doc.version());return List.of(new float[]{1,0,0});});
        assertFalse(worker.handle(body,null).acknowledge());assertEquals("CANCELLED",indexes.find(event(doc).indexGeneration(),false).status());assertTrue(worker.handle(body,null).acknowledge());assertTrue(query("HTTP",SearchService.Mode.keyword).isEmpty());}
    @Test void failedReindexPreservesOldValidHead(){var doc=indexed("HTTP 503 old valid index");UUID prior=event(doc).indexGeneration();UUID next=indexes.reindex(org,doc.id(),doc.currentRevision().id());assertNotNull(next);
        var event=ingestion.events().stream().filter(e->next.equals(e.indexGeneration())).findFirst().orElseThrow();when(embeddings.documents(anyList())).thenThrow(new EmbeddingFailure("EMBEDDING_UNAVAILABLE"));
        assertFalse(worker.handle(event.body(),null).acknowledge());assertEquals(prior,query("HTTP",SearchService.Mode.keyword).getFirst().source().generationId());
    }
    @Test void forgedIndexMessagesCannotReadStorage()throws Exception{var doc=upload(org,"alice","HTTP 503");var m=IndexMessage.parse(prepare(doc));reset(storage);
        var forged=new IndexMessage(m.event(),m.job(),foreign,m.document(),m.revision(),m.generation());assertFalse(worker.handle(forged.encode(),null).acknowledge());verify(storage,never()).read(anyString(),any(),anyLong());assertEquals(0,indexes.find(m.generation(),false).attempts());
        assertFalse(verification.handle(new IngestionMessage(m.event(),m.job(),m.organization(),m.document(),m.revision()).encode()).acknowledge());
    }
    @Test void keywordSurvivesSemanticOutageAndPureSemanticFails(){indexed("HTTP 503");when(embeddings.query(anyString())).thenThrow(new EmbeddingFailure("EMBEDDING_UNAVAILABLE"));
        var result=search.search(token("alice"),org,"HTTP 503",10,SearchService.Mode.hybrid);assertEquals(SearchService.Mode.keyword,result.effectiveMode());assertEquals(1,result.returnedCount());assertThrows(EmbeddingFailure.class,()->query("HTTP 503",SearchService.Mode.semantic));}
    @Test void invalidQueryAndResultLimitsAreEnforced()throws Exception{indexed("HTTP 503");indexed("HTTP 503 second");assertEquals(1,search.search(token("alice"),org,"HTTP",1,SearchService.Mode.keyword).returnedCount());
        for(String q:List.of("","x".repeat(257),"a ".repeat(17)))mvc.perform(get(path(org)).param("q",q).with(jwt().jwt(t->t.issuer("https://issuer.example").subject("alice").claim("traceback_principal_type","human")))).andExpect(status().isBadRequest());
        mvc.perform(get(path(org)).param("q","HTTP").param("limit","51").with(jwt().jwt(t->t.issuer("https://issuer.example").subject("alice").claim("traceback_principal_type","human")))).andExpect(status().isBadRequest());}
    @Test void vectorRankingActuallyUsesCosineAndHybridDeduplicates(){var first=indexed("HTTP 503 database");var second=indexed("HTTP 503 api");
        jdbc.update("UPDATE chunk_embeddings SET embedding='[0,1,0]'::vector WHERE document_id=?",second.id());
        assertEquals(first.id(),query("HTTP 503",SearchService.Mode.semantic).getFirst().source().documentId());var results=query("HTTP 503",SearchService.Mode.hybrid);assertEquals(2,results.size());assertEquals(2,results.stream().map(r->r.source().key()).distinct().count());}

    @Test void publicationCommitFailureRollsBackAllChunksAndRecovers(){var doc=upload(org,"alice","HTTP 503 publication rollback");String body=prepare(doc);
        jdbc.execute("CREATE FUNCTION m12_reject_head() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
        jdbc.execute("CREATE TRIGGER m12_reject_head BEFORE INSERT ON document_index_heads FOR EACH ROW EXECUTE FUNCTION m12_reject_head()");
        try{assertThrows(DataAccessException.class,()->worker.handle(body,null));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM document_chunks",Integer.class));assertTrue(query("HTTP",SearchService.Mode.keyword).isEmpty());}
        finally{jdbc.execute("DROP TRIGGER m12_reject_head ON document_index_heads");jdbc.execute("DROP FUNCTION m12_reject_head()");}
        jdbc.update("UPDATE index_generations SET lease_until=clock_timestamp()-interval '1 second'");assertTrue(worker.handle(body,null).acknowledge());assertEquals(1,query("HTTP",SearchService.Mode.keyword).size());
    }
    @Test void deferredDatabaseCommitFailureRollsBackPublishedHeadAndRecovers() {
        var doc=upload(org,"alice","HTTP 503 deferred commit failure");String body=prepare(doc);
        double before=metrics.counter("traceback.indexing.publication.failures").count();
        jdbc.execute("CREATE FUNCTION m15_fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic deferred commit failure'; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER m15_fail_commit AFTER INSERT ON document_index_heads DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION m15_fail_commit()");
        try {
            assertThrows(RuntimeException.class,()->worker.handle(body,null));
            assertEquals(before+1,metrics.counter("traceback.indexing.publication.failures").count());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM document_chunks",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM document_index_heads",Integer.class));
            assertNotEquals("INDEXED",indexes.find(event(doc).indexGeneration(),false).status());
            assertTrue(query("HTTP",SearchService.Mode.keyword).isEmpty());
        } finally {
            jdbc.execute("DROP TRIGGER m15_fail_commit ON document_index_heads");jdbc.execute("DROP FUNCTION m15_fail_commit()");
        }
        jdbc.update("UPDATE index_generations SET lease_until=clock_timestamp()-interval '1 second'");
        assertTrue(worker.handle(body,null).acknowledge());assertEquals(1,query("HTTP",SearchService.Mode.keyword).size());
    }
    @Test void renewalIsTokenFencedAndTotalDeadlineCannotBeExtended(){var doc=upload(org,"alice","HTTP 503");var claimed=indexes.claim(IndexMessage.parse(prepare(doc))).job();
        assertTrue(indexes.heartbeat(claimed));jdbc.update("UPDATE index_generations SET started_at=clock_timestamp()-interval '11 minutes'");assertFalse(indexes.heartbeat(claimed));
    }
    @Test void replacingRevisionDuringEmbeddingCannotPublishStaleOutput(){var doc=upload(org,"alice","HTTP 503 stale");String body=prepare(doc);
        when(embeddings.documents(anyList())).thenAnswer(c->{documents.revise(token("alice"),org,doc.id(),doc.version(),text("replacement current"));return List.of(new float[]{1,0,0});});
        assertFalse(worker.handle(body,null).acknowledge());assertTrue(query("HTTP",SearchService.Mode.keyword).isEmpty());assertEquals("CANCELLED",indexes.find(event(doc).indexGeneration(),false).status());
    }
    @Test void lostIndexDeliveryRearmsOnlyExpiredUnfinishedWork(){var doc=upload(org,"alice","HTTP 503");var m=IndexMessage.parse(prepare(doc));indexes.claim(m);
        jdbc.update("UPDATE ingestion_outbox SET delivered_at=clock_timestamp()-interval '10 minutes' WHERE id=?",m.event());
        assertEquals(0,indexes.reconcile());jdbc.update("UPDATE index_generations SET updated_at=clock_timestamp()-interval '10 minutes',lease_until=clock_timestamp()-interval '1 second'");
        assertEquals(1,indexes.reconcile());assertTrue(worker.handle(m.encode(),null).acknowledge());assertEquals(0,indexes.reconcile());
    }
    @Test void repeatedReindexRequestsShareUnfinishedGenerationAndSuccessfulSwitchIsAtomic(){var doc=indexed("HTTP 503 stable");UUID old=event(doc).indexGeneration();UUID next=indexes.reindex(org,doc.id(),doc.currentRevision().id());
        assertEquals(next,indexes.reindex(org,doc.id(),doc.currentRevision().id()));assertEquals(old,query("HTTP",SearchService.Mode.keyword).getFirst().source().generationId());
        var event=ingestion.events().stream().filter(e->next.equals(e.indexGeneration())).findFirst().orElseThrow();assertTrue(worker.handle(event.body(),null).acknowledge());
        assertEquals(next,query("HTTP",SearchService.Mode.keyword).getFirst().source().generationId());assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM document_chunks",Integer.class));
    }
    @Test void sqlRejectsCrossTenantChunksAndWrongDimensions(){var doc=indexed("HTTP 503");UUID generation=event(doc).indexGeneration();
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE document_chunks SET organization_id=? WHERE generation_id=?",foreign,generation));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE chunk_embeddings SET embedding='[1,0]'::vector WHERE generation_id=?",generation));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE index_configurations SET dimensions=2 WHERE id=?",settings.configuration()));
    }
    @Test void verificationAndIndexEventCreationRollbackTogether(){var doc=upload(org,"alice","HTTP 503");var message=ingestion.events().getFirst().body();
        jdbc.execute("CREATE FUNCTION m12_reject_index_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='INDEX' THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER m12_reject_index_event BEFORE INSERT ON ingestion_outbox FOR EACH ROW EXECUTE FUNCTION m12_reject_index_event()");
        try{assertThrows(DataAccessException.class,()->verification.handle(message));assertEquals("RUNNING",ingestion.find(org,doc.id(),doc.currentRevision().id(),false).status());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM index_generations",Integer.class));}
        finally{jdbc.execute("DROP TRIGGER m12_reject_index_event ON ingestion_outbox");jdbc.execute("DROP FUNCTION m12_reject_index_event()");}
        jdbc.update("UPDATE ingestion_jobs SET lease_until=clock_timestamp()-interval '1 second'");assertTrue(verification.handle(message).acknowledge());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM index_generations",Integer.class));
    }
    @Test void administrativeReindexScriptIsScopedAndIdempotentWhilePending()throws Exception{var doc=indexed("HTTP 503");
        String script=java.nio.file.Files.readString(java.nio.file.Path.of("../scripts/indexing/reindex.sql")).replace(":'org'","'"+org+"'").replace(":'doc'","'"+doc.id()+"'").replace(":'rev'","'"+doc.currentRevision().id()+"'").replace(":'config'","'"+settings.configuration()+"'");
        jdbc.execute(script);jdbc.execute(script);assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM index_generations",Integer.class));
        documents.delete(token("alice"),org,doc.id(),doc.version());jdbc.execute(script);assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM index_generations",Integer.class));
    }
    @Test void embeddingRetriesAreBoundedAndTerminalDuplicatesDoNotConsumeAttempts(){var doc=upload(org,"alice","HTTP 503");String body=prepare(doc);
        when(embeddings.documents(anyList())).thenThrow(new EmbeddingFailure("EMBEDDING_UNAVAILABLE"));
        for(int i=0;i<5;i++){jdbc.update("UPDATE index_generations SET next_attempt_at=clock_timestamp()-interval '1 second'");assertFalse(worker.handle(body,null).acknowledge());}
        var generation=indexes.find(event(doc).indexGeneration(),false);assertEquals("FAILED",generation.status());assertEquals(5,generation.attempts());
        assertFalse(worker.handle(body,null).acknowledge());assertEquals(5,indexes.find(generation.id(),false).attempts());assertTrue(query("HTTP",SearchService.Mode.keyword).isEmpty());
    }
    @Test void staleJobRejectionCannotCancelTheCurrentRevisionGeneration(){var old=upload(org,"alice","HTTP 503 old");String oldBody=prepare(old);UUID oldGeneration=event(old).indexGeneration();
        var current=documents.revise(token("alice"),org,old.id(),old.version(),text("new current"));prepare(current);UUID newGeneration=event(current).indexGeneration();
        // Inject a missed cancellation, independently exercising the eligibility defense.
        jdbc.update("UPDATE index_generations SET status='PENDING_EXTRACTION' WHERE id=?",oldGeneration);
        assertTrue(worker.handle(oldBody,null).acknowledge());assertEquals("CANCELLED",indexes.find(oldGeneration,false).status());assertEquals("PENDING_EXTRACTION",indexes.find(newGeneration,false).status());
    }
    @Test void repeatedHeadingMetadataIsBoundedBeforeJsonSerialization(){var doc=upload(org,"alice","HTTP 503");var job=indexes.claim(IndexMessage.parse(prepare(doc))).job();
        var blocks=Collections.nCopies(2000,new dev.traceback.extraction.SourceBlock("synthetic","s".repeat(1600),null,0,9));
        assertEquals("STRUCTURE_LIMIT",assertThrows(dev.traceback.extraction.ExtractionFailure.class,()->indexes.extracted(job,blocks)).code());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM extraction_artifacts",Integer.class));
    }
}
