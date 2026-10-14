package dev.traceback.ingestion;

import dev.traceback.TracebackBackendApplication;
import dev.traceback.knowledge.*;
import dev.traceback.knowledge.dto.DocumentResponse;
import dev.traceback.knowledge.storage.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(properties={"traceback.aws.enabled=true","traceback.documents.provider=S3","traceback.ingestion.scheduling-enabled=false"})
class DurableIngestionIntegrationTest {
    static final String ROOT=TestDocumentStorage.directory();
    static final String BUCKET="traceback-documents-local";
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> aws=new GenericContainer<>("localstack/localstack:4.8.1")
            .withEnv("SERVICES","s3,sqs").withEnv("SQS_ENDPOINT_STRATEGY","path")
            .withCopyFileToContainer(MountableFile.forHostPath("../scripts/localstack/10-init.sh",0755),"/etc/localstack/init/ready.d/10-init.sh")
            .withExposedPorts(4566).waitingFor(Wait.forLogMessage(".*Ready\\.\\n",1)).withStartupTimeout(Duration.ofMinutes(3));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("traceback.documents.storage-directory",()->ROOT);
        r.add("traceback.aws.endpoint",()->"http://localhost:"+aws.getMappedPort(4566));
    }
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean DocumentStorage storage;
    @MockitoSpyBean IngestionQueue queue;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentService documents;
    @Autowired DocumentRepository documentRepository;
    @Autowired IngestionRepository jobs;
    @Autowired IngestionWorker worker;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired QueueConsumer consumer;
    @Autowired PlatformTransactionManager manager;
    @Autowired S3Client s3;
    @Autowired SqsClient sqs;
    UUID org,foreignOrg,user;
    String queueUrl,dlqUrl;

    @BeforeEach void setup() throws Exception {
        reset(storage,queue);
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(()->queue.ready());
        queueUrl=sqs.getQueueUrl(b->b.queueName("traceback-ingestion-local")).queueUrl();
        dlqUrl=sqs.getQueueUrl(b->b.queueName("traceback-ingestion-dlq-local")).queueUrl();
        drain(queueUrl);drain(dlqUrl);
        jdbc.update("UPDATE documents SET current_revision_id=NULL,status='DELETED'");
        jdbc.update("DELETE FROM ingestion_outbox");
        for(String table:List.of("document_index_heads","chunk_embeddings","document_chunks","extraction_artifacts","index_generations"))jdbc.update("DELETE FROM "+table);
        jdbc.update("DELETE FROM ingestion_jobs");jdbc.update("DELETE FROM document_revisions");jdbc.update("DELETE FROM documents");
        jdbc.update("DELETE FROM organization_memberships");jdbc.update("DELETE FROM organizations");jdbc.update("DELETE FROM app_users");
        org=UUID.randomUUID();foreignOrg=UUID.randomUUID();user=UUID.randomUUID();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?,'A'),(?,'B')",org,foreignOrg);
        jdbc.update("INSERT INTO app_users(id,issuer,subject) VALUES (?,'https://issuer.example','alice')",user);
        jdbc.update("INSERT INTO organization_memberships(id,organization_id,user_id,role) VALUES (?,?,?,'ADMIN')",UUID.randomUUID(),org,user);
    }
    void drain(String url) {
        for (int i=0;i<30;i++) {
            var messages=sqs.receiveMessage(b->b.queueUrl(url).maxNumberOfMessages(10).visibilityTimeout(0)).messages();
            if (messages.isEmpty()) return;
            for (var m:messages) sqs.deleteMessage(b->b.queueUrl(url).receiptHandle(m.receiptHandle()));
        }
        fail("Queue did not drain");
    }
    Jwt human() { return Jwt.withTokenValue("synthetic").header("alg","RS256").issuer("https://issuer.example").subject("alice").claim("traceback_principal_type","human").build(); }
    MockMultipartFile text(String content) { return new MockMultipartFile("file","runbook.txt","text/plain",content.getBytes(StandardCharsets.UTF_8)); }
    DocumentResponse upload() { return documents.upload(human(),org,"Runbook",text("original content")); }
    IngestionRepository.Job job(DocumentResponse d) { return jobs.find(org,d.id(),d.currentRevision().id(),false); }
    IngestionRepository.Event event(DocumentResponse d) { return jobs.events().stream().filter(e->e.message().revision().equals(d.currentRevision().id())).findFirst().orElseThrow(); }
    void makeDue(UUID id) { jdbc.update("UPDATE ingestion_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",id); }
    List<Message> receive(String url) { return sqs.receiveMessage(b->b.queueUrl(url).maxNumberOfMessages(10).visibilityTimeout(0)).messages(); }

    @Test void s3RoundtripPrivateConfigurationOpaqueKeysAndImmutableConditionalPut() throws Exception {
        var doc=upload();var job=job(doc);
        assertEquals("S3",job.provider());assertEquals("original content",new String(documents.download(human(),org,doc.id(),null).bytes(),StandardCharsets.UTF_8));
        var object=s3.headObject(b->b.bucket(BUCKET).key("documents/"+job.key()));
        assertEquals(ServerSideEncryption.AES256,object.serverSideEncryption());
        assertTrue(s3.getPublicAccessBlock(b->b.bucket(BUCKET)).publicAccessBlockConfiguration().blockPublicPolicy());
        assertTrue(s3.getBucketAcl(b->b.bucket(BUCKET)).grants().stream().noneMatch(g->g.grantee().type()==Type.GROUP));
        assertThrows(IOException.class,()->storage.put(job.key(),"overwrite".getBytes()));
        assertEquals(job.checksum(),DocumentUploadValidator.checksum(storage.read("S3",job.key(),10485760)));
        var adapter=new S3DocumentStorage(s3,BUCKET,16,false);
        assertThrows(StorageFailure.class,()->adapter.put(UUID.randomUUID(),new byte[17]));
        assertThrows(StorageFailure.class,()->adapter.read(job.key(),2));
        assertFalse(doc.toString().contains(job.key().toString()));
    }
    @Test void legacyLocalRevisionAccessibleAlongsideS3WithoutAutomaticBackfill() throws Exception {
        Path localPath=Files.createTempDirectory("m11-legacy-").toRealPath();
        var local=new LocalDocumentStorage(localPath.toString());
        try (local) {
            UUID doc=UUID.randomUUID(),rev=UUID.randomUUID(),key=UUID.randomUUID();byte[] bytes="legacy".getBytes();
            local.put(key,bytes);
            // Emulate an AVAILABLE pre-V6 record through an explicit persisted LOCAL provider.
            jdbc.update("INSERT INTO documents(id,organization_id,title,created_by,status) VALUES (?,?,'Legacy',?,'UPLOADING')",doc,org,user);
            jdbc.update("""
                INSERT INTO document_revisions(id,organization_id,document_id,revision_number,original_filename,media_type,file_size,checksum,storage_key,uploaded_by,status,storage_provider)
                VALUES (?,?,?,1,'legacy.txt','text/plain',?,?,?,?, 'AVAILABLE','LOCAL')
                """,rev,org,doc,bytes.length,DocumentUploadValidator.checksum(bytes),key,user);
            jdbc.update("UPDATE documents SET status='AVAILABLE',current_revision_id=? WHERE id=?",rev,doc);
            Files.copy(localPath.resolve(key+".blob"),Path.of(ROOT).resolve(key+".blob"));
            assertArrayEquals(bytes,documents.download(human(),org,doc,rev).bytes());
            assertEquals("NOT_SCHEDULED",documents.ingestionStatus(human(),org,doc,rev).status());
            new TransactionTemplate(manager).executeWithoutResult(t-> {
                jdbc.queryForObject("SELECT id FROM documents WHERE id=? FOR UPDATE",UUID.class,doc);
                jobs.enqueue(org,doc,rev); // Explicit test scheduling, not automatic backfill.
            });
            var legacyEvent=jobs.events().stream().filter(e->e.message().revision().equals(rev)).findFirst().orElseThrow();
            assertTrue(worker.handle(legacyEvent.message().encode()).acknowledge());
            assertEquals("VERIFIED",jobs.find(org,doc,rev,false).status());
            assertEquals("S3",job(upload()).provider());
        }
    }
    @Test void publicationAndOutboxRollbackTogether() {
        jdbc.execute("CREATE FUNCTION m11_reject_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END $$");
        jdbc.execute("CREATE TRIGGER m11_reject_event BEFORE INSERT ON ingestion_outbox FOR EACH ROW EXECUTE FUNCTION m11_reject_event()");
        try {
            assertThrows(DocumentException.class,this::upload);
            assertEquals(0,jobs.pendingEvents());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ingestion_jobs",Integer.class));
            assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM document_revisions",String.class));
        } finally { jdbc.execute("DROP TRIGGER m11_reject_event ON ingestion_outbox");jdbc.execute("DROP FUNCTION m11_reject_event()"); }
    }
    @Test void publishOutageRetriesAndCrashAfterSendDuplicatesSafely() {
        var doc=upload();var message=event(doc).message();
        doThrow(new IllegalStateException("injected outage")).when(queue).send(anyString());
        assertTrue(dispatcher.dispatchOne());assertEquals(1,jobs.pendingEvents());assertEquals(1,jobs.publicationFailures());
        reset(queue);jdbc.update("UPDATE ingestion_outbox SET next_attempt_at=clock_timestamp()-interval '1 second'");
        var claimed=jobs.claimEvent();queue.send(claimed.message().encode()); // crash before delivered commit
        jdbc.update("UPDATE ingestion_outbox SET lease_until=clock_timestamp()-interval '1 second'");
        dispatcher.dispatchOne();assertEquals(0,jobs.pendingEvents());
        assertTrue(worker.handle(message.encode()).acknowledge());assertTrue(worker.handle(message.encode()).acknowledge());
        assertEquals("VERIFIED",job(doc).status());assertEquals(1,job(doc).attempts());
        assertEquals(2,receive(queueUrl).size());
    }
    @Test void expiredOutboxTokenCannotRecordSuccessForNewClaim() {
        upload();var first=jobs.claimEvent();
        jdbc.update("UPDATE ingestion_outbox SET lease_until=clock_timestamp()-interval '1 second'");
        var next=jobs.claimEvent();assertNotEquals(first.token(),next.token());assertFalse(jobs.delivered(first));assertTrue(jobs.delivered(next));
    }
    @Test void concurrentDispatchersClaimDifferentEventsAndPublishAfterQueueRecovery() throws Exception {
        upload();upload();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(jobs::claimEvent);var b=pool.submit(jobs::claimEvent);
            var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);
            assertNotEquals(first.message().event(),second.message().event());assertNull(jobs.claimEvent());
            jobs.publishFailed(first,1);jobs.publishFailed(second,1);
        }
        jdbc.update("UPDATE ingestion_outbox SET next_attempt_at=clock_timestamp()-interval '1 second'");
        assertTrue(dispatcher.dispatchOne());assertTrue(dispatcher.dispatchOne());assertEquals(0,jobs.pendingEvents());
    }
    @Test void duplicateAndOutOfOrderSupersededRevisionCannotOverwriteCurrent() {
        var original=upload();var old=event(original).message();
        var next=documents.revise(human(),org,original.id(),original.version(),text("new content"));
        assertEquals("CANCELLED",job(original).status());assertTrue(worker.handle(old.encode()).acknowledge());
        assertTrue(worker.handle(event(next).message().encode()).acknowledge());assertTrue(worker.handle(event(next).message().encode()).acknowledge());
        assertEquals("VERIFIED",job(next).status());assertEquals(1,job(next).attempts());
        assertEquals(next.currentRevision().id(),documents.metadata(human(),org,next.id()).currentRevision().id());
    }
    @Test void concurrentWorkersAndCrashLeaseRecoveryFenceOldCompletion() throws Exception {
        var doc=upload();var message=event(doc).message();
        IngestionRepository.Claim first,second;
        try (var pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch gate=new CountDownLatch(1);
            var a=pool.submit(()-> { gate.await();return jobs.claim(message); });var b=pool.submit(()-> { gate.await();return jobs.claim(message); });gate.countDown();
            first=a.get(10,TimeUnit.SECONDS);second=b.get(10,TimeUnit.SECONDS);
        }
        assertEquals(Set.of("CLAIMED","BUSY"),Set.of(first.outcome(),second.outcome()));
        var crashed=first.job()!=null ? first.job() : second.job();
        assertFalse(worker.handle(message.encode()).acknowledge());
        jdbc.update("UPDATE ingestion_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",crashed.id());
        assertEquals(1,jobs.expiredLeases());
        assertTrue(worker.handle(message.encode()).acknowledge());assertEquals(2,job(doc).attempts());
        assertFalse(jobs.finish(crashed,null,false,1,1));assertEquals("VERIFIED",job(doc).status());
    }
    @Test void staleLeaseMustNotCompleteEvenWithoutAnotherWorker() {
        var doc=upload();var claimed=jobs.claim(event(doc).message()).job();
        jdbc.update("UPDATE ingestion_jobs SET lease_until=clock_timestamp()-interval '1 second'");
        assertFalse(jobs.finish(claimed,null,false,1,1));assertEquals("RUNNING",job(doc).status());
    }
    @Test void temporaryStorageOutageRetriesWithoutAcknowledgingThenRecovers() throws Exception {
        var doc=upload();var message=event(doc).message();
        doThrow(new IOException("injected sensitive details")).when(storage).read(eq("S3"),any(),anyLong());
        var result=worker.handle(message.encode());assertFalse(result.acknowledge());assertTrue(result.delaySeconds()>0);
        assertEquals("RETRY",job(doc).status());assertEquals("STORAGE_UNAVAILABLE",job(doc).errorCode());
        assertFalse(worker.handle(message.encode()).acknowledge());assertEquals(1,job(doc).attempts());
        reset(storage);makeDue(job(doc).id());assertTrue(worker.handle(message.encode()).acknowledge());assertEquals("VERIFIED",job(doc).status());
    }
    @Test void boundedRetriesBecomeTerminalAndSanitized() throws Exception {
        var doc=upload();doThrow(new IOException("private path and content")).when(storage).read(eq("S3"),any(),anyLong());
        for (int i=0;i<5;i++) { makeDue(job(doc).id());assertFalse(worker.handle(event(doc).message().encode()).acknowledge()); }
        assertEquals("FAILED",job(doc).status());assertEquals(5,job(doc).attempts());assertEquals("RETRIES_EXHAUSTED",job(doc).errorCode());
        assertFalse(worker.handle(event(doc).message().encode()).acknowledge());assertEquals(5,job(doc).attempts());
    }
    @Test void corruptAndMissingContentArePermanent() {
        var corrupt=upload();s3.putObject(b->b.bucket(BUCKET).key("documents/"+job(corrupt).key()),RequestBody.fromString("tampered"));
        assertFalse(worker.handle(event(corrupt).message().encode()).acknowledge());assertEquals("CONTENT_CORRUPT",job(corrupt).errorCode());
        var missing=upload();s3.deleteObject(b->b.bucket(BUCKET).key("documents/"+job(missing).key()));
        assertFalse(worker.handle(event(missing).message().encode()).acknowledge());assertEquals("CONTENT_MISSING",job(missing).errorCode());
    }
    @Test void forgedOrganizationDocumentJobAndRevisionReferencesNeverReadContent() throws Exception {
        var doc=upload();var m=event(doc).message();
        for (var forged:List.of(new IngestionMessage(m.event(),m.job(),foreignOrg,m.document(),m.revision()),
                new IngestionMessage(m.event(),UUID.randomUUID(),m.organization(),m.document(),m.revision()),
                new IngestionMessage(m.event(),m.job(),m.organization(),UUID.randomUUID(),m.revision()),
                new IngestionMessage(m.event(),m.job(),m.organization(),m.document(),UUID.randomUUID()),
                new IngestionMessage(UUID.randomUUID(),m.job(),m.organization(),m.document(),m.revision()))) {
            assertFalse(worker.handle(forged.encode()).acknowledge());
        }
        verify(storage,never()).read(anyString(),any(),anyLong());assertEquals(0,job(doc).attempts());
        assertThrows(RuntimeException.class,()->documents.ingestionStatus(human(),foreignOrg,doc.id(),m.revision()));
        assertFalse(worker.handle("v2:untrusted document instructions").acknowledge());
    }
    @Test void deletionAndSupersessionDuringReadFenceCompletion() throws Exception {
        var deleted=upload();var old=event(deleted).message();
        doAnswer(call->{ documents.delete(human(),org,deleted.id(),deleted.version());return call.callRealMethod(); })
                .when(storage).read(eq("S3"),eq(job(deleted).key()),anyLong());
        assertTrue(worker.handle(old.encode()).acknowledge());assertEquals("CANCELLED",job(deleted).status());
        assertThrows(DocumentException.class,()->documents.download(human(),org,deleted.id(),null));
        reset(storage);
        var replaced=upload();AtomicBoolean changed=new AtomicBoolean();
        doAnswer(call->{ if (changed.compareAndSet(false,true)) documents.revise(human(),org,replaced.id(),replaced.version(),text("replacement"));return call.callRealMethod(); })
                .when(storage).read(eq("S3"),eq(job(replaced).key()),anyLong());
        assertTrue(worker.handle(event(replaced).message().encode()).acknowledge());assertEquals("CANCELLED",job(replaced).status());
        assertNull(job(replaced).verifiedAt());
    }
    @Test void deletedBeforeDeliveryNeverReadsStorage() throws Exception {
        var doc=upload();documents.delete(human(),org,doc.id(),doc.version());reset(storage);
        assertTrue(worker.handle(event(doc).message().encode()).acknowledge());verify(storage,never()).read(anyString(),any(),anyLong());
    }
    @Test void acknowledgmentOnlyAfterDurableCompletionAndAckOutageRedeliversIdempotently() {
        var doc=upload();dispatcher.dispatchOne();var receipt=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call->{ assertEquals("VERIFIED",job(doc).status());receipt.set(call.getArgument(0));throw new IllegalStateException("ack outage"); })
                .when(queue).acknowledge(anyString());
        assertThrows(IllegalStateException.class,consumer::pollOnce);assertEquals("VERIFIED",job(doc).status());
        assertTrue(receive(queueUrl).isEmpty()); // Failed acknowledgement leaves the real message invisible.
        sqs.changeMessageVisibility(b->b.queueUrl(queueUrl).receiptHandle(receipt.get()).visibilityTimeout(0));
        reset(queue);consumer.pollOnce();assertEquals(1,job(doc).attempts());assertTrue(receive(queueUrl).isEmpty());
    }
    @Test void completionCommitFailureNeverAcknowledgesAndRecoversAfterLeaseExpiry() {
        var doc=upload();dispatcher.dispatchOne();var receipt=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call->{ @SuppressWarnings("unchecked") var deliveries=(List<IngestionQueue.Delivery>)call.callRealMethod();
            receipt.set(deliveries.getFirst().receipt());return deliveries; }).when(queue).receive();
        jdbc.execute("CREATE FUNCTION m11_reject_completion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='VERIFIED' THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER m11_reject_completion BEFORE UPDATE ON ingestion_jobs FOR EACH ROW EXECUTE FUNCTION m11_reject_completion()");
        try {
            assertThrows(RuntimeException.class,consumer::pollOnce);assertEquals("RUNNING",job(doc).status());verify(queue,never()).acknowledge(anyString());
        } finally { jdbc.execute("DROP TRIGGER m11_reject_completion ON ingestion_jobs");jdbc.execute("DROP FUNCTION m11_reject_completion()"); }
        jdbc.update("UPDATE ingestion_jobs SET lease_until=clock_timestamp()-interval '1 second'");
        sqs.changeMessageVisibility(b->b.queueUrl(queueUrl).receiptHandle(receipt.get()).visibilityTimeout(0));
        reset(queue);consumer.pollOnce();assertEquals("VERIFIED",job(doc).status());assertEquals(2,job(doc).attempts());
    }
    @Test void interruptedS3PutAndCleanupOutageKeepFailedUploadInaccessible() throws Exception {
        doAnswer(call->{ call.callRealMethod();throw new IOException("lost upload acknowledgement"); }).when(storage).put(any(),any());
        doThrow(new IOException("cleanup outage")).when(storage).delete(eq("S3"),any());
        assertThrows(DocumentException.class,this::upload);
        UUID rev=jdbc.queryForObject("SELECT id FROM document_revisions",UUID.class);
        UUID doc=jdbc.queryForObject("SELECT document_id FROM document_revisions",UUID.class);
        UUID key=jdbc.queryForObject("SELECT storage_key FROM document_revisions",UUID.class);
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM document_revisions",String.class));assertEquals(0,jobs.pendingEvents());
        assertThrows(DocumentException.class,()->documents.download(human(),org,doc,rev));
        assertNotNull(s3.headObject(b->b.bucket(BUCKET).key("documents/"+key))); // Private orphan has a durable failed reservation.
        reset(storage);storage.delete("S3",key);storage.delete("S3",key);assertThrows(StorageFailure.class,()->storage.read("S3",key,10485760));
    }
    @Test void actualSqsVisibilityRedeliveryAndPoisonDlqRedrive() {
        queue.send("poison");
        var first=sqs.receiveMessage(b->b.queueUrl(queueUrl).visibilityTimeout(150)).messages().getFirst();
        assertTrue(receive(queueUrl).isEmpty());
        sqs.changeMessageVisibility(b->b.queueUrl(queueUrl).receiptHandle(first.receiptHandle()).visibilityTimeout(0));
        var second=receive(queueUrl).getFirst();assertEquals(first.messageId(),second.messageId());assertNotEquals(first.receiptHandle(),second.receiptHandle());
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(()-> {
            receive(queueUrl);return !receive(dlqUrl).isEmpty();
        });
        var dead=receive(dlqUrl);assertFalse(dead.isEmpty());assertEquals("poison",dead.getFirst().body());
        // Real emulator redrive operation, not a configured-only assertion.
        var dlqArn=sqs.getQueueAttributes(b->b.queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        sqs.startMessageMoveTask(b->b.sourceArn(dlqArn).maxNumberOfMessagesPerSecond(10));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(()-> !receive(queueUrl).isEmpty());
    }
    @Test void failedJobMovesThroughConsumerToDlq() {
        var doc=upload();s3.deleteObject(b->b.bucket(BUCKET).key("documents/"+job(doc).key()));dispatcher.dispatchOne();consumer.pollOnce();
        assertEquals("FAILED",job(doc).status());
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(()-> {
            for (var message:receive(queueUrl)) sqs.changeMessageVisibility(b->b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()).visibilityTimeout(0));
            return !receive(dlqUrl).isEmpty();
        });
        assertEquals(1,job(doc).attempts());
    }
    @Test void recoverLostDeliveryAndExpiredWorkerWithoutResettingLiveWork() {
        var doc=upload();dispatcher.dispatchOne();jobs.claim(event(doc).message());
        assertEquals(0,jobs.reconcile());
        jdbc.update("UPDATE ingestion_jobs SET lease_until=clock_timestamp()-interval '1 second',updated_at=clock_timestamp()-interval '10 minutes',next_attempt_at=clock_timestamp()-interval '10 minutes'");
        jdbc.update("UPDATE ingestion_outbox SET delivered_at=clock_timestamp()-interval '10 minutes'");
        assertEquals(1,jobs.reconcile());dispatcher.dispatchOne();assertEquals(0,jobs.reconcile());
        assertTrue(worker.handle(event(doc).message().encode()).acknowledge());assertEquals("VERIFIED",job(doc).status());
    }
    @Test void developmentRetryCreatesNewGenerationAndRejectsOldEventsAndDeletedJobs() throws Exception {
        var doc=upload();var old=event(doc).message();var persisted=job(doc);
        s3.deleteObject(b->b.bucket(BUCKET).key("documents/"+persisted.key()));
        assertFalse(worker.handle(old.encode()).acknowledge());
        String script=Files.readString(Path.of("../scripts/ingestion/retry.sql"))
                .replace(":'org'","'"+org+"'").replace(":'doc'","'"+doc.id()+"'").replace(":'rev'","'"+persisted.revision()+"'");
        jdbc.execute(script);
        assertEquals("PENDING",job(doc).status());assertEquals(1,job(doc).generation());assertEquals(0,job(doc).attempts());
        assertTrue(worker.handle(old.encode()).acknowledge());assertEquals("PENDING",job(doc).status());
        s3.putObject(b->b.bucket(BUCKET).key("documents/"+persisted.key()),RequestBody.fromString("original content"));
        var next=jobs.events().stream().filter(e->e.generation()==1).findFirst().orElseThrow();
        assertTrue(worker.handle(next.message().encode()).acknowledge());assertEquals("VERIFIED",job(doc).status());
        documents.delete(human(),org,doc.id(),doc.version());jdbc.execute(script);
        assertEquals("CANCELLED",job(doc).status());assertEquals(2,jobs.events().size());
    }
    @Test void sdkQueuePublicationFailureKeepsEventDurableForLaterRecovery() {
        var doc=upload();
        var missing=new SqsIngestionQueue(sqs,"nonexistent-local-queue",new IngestionSettings(120,150,5,2,60,180));
        doAnswer(call->{ missing.send(call.getArgument(0));return null; }).when(queue).send(anyString());
        assertTrue(dispatcher.dispatchOne());assertEquals(1,jobs.publicationFailures());assertEquals(1,jobs.pendingEvents());
        reset(queue);jdbc.update("UPDATE ingestion_outbox SET next_attempt_at=clock_timestamp()-interval '1 second'");
        dispatcher.dispatchOne();consumer.pollOnce();assertEquals("VERIFIED",job(doc).status());
    }
    @Test void separatelyExecutableWorkerDispatchesAndConsumesWithScheduling() throws Exception {
        var doc=upload();
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("java.class.path"),TracebackBackendApplication.class.getName(),
                "--spring.profiles.active=worker","--server.port=0","--spring.datasource.url="+postgres.getJdbcUrl(),
                "--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword(),
                "--traceback.documents.storage-directory="+ROOT,"--traceback.aws.endpoint=http://localhost:"+aws.getMappedPort(4566))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).until(()-> {
                assertTrue(process.isAlive(),"Worker process exited before verification");
                return job(doc).status().equals("VERIFIED");
            });
            assertEquals(1,job(doc).attempts());assertEquals(0,jobs.pendingEvents());
        } finally {
            process.destroy();if (!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS)); }
        }
    }
    @Test void independentWorkerSharesDatabaseAndLegacyRootWithoutApiLockAndDeniesHttpApi() throws Exception {
        var doc=upload();
        try (var context=new SpringApplicationBuilder(TracebackBackendApplication.class).profiles("worker").run(
                "--server.port=0","--spring.datasource.url="+postgres.getJdbcUrl(),"--spring.datasource.username="+postgres.getUsername(),
                "--spring.datasource.password="+postgres.getPassword(),"--traceback.documents.storage-directory="+ROOT,
                "--traceback.aws.endpoint=http://localhost:"+aws.getMappedPort(4566),"--traceback.ingestion.scheduling-enabled=false")) {
            assertTrue(context.getBean(IngestionWorker.class).handle(event(doc).message().encode()).acknowledge());
            assertEquals("VERIFIED",job(doc).status());
            assertThrows(IOException.class,()->context.getBean(LocalDocumentStorage.class).put(UUID.randomUUID(),"no".getBytes()));
            var server=(org.springframework.boot.web.server.context.WebServerApplicationContext)context;
            int port=server.getWebServer().getPort();var client=HttpClient.newHttpClient();
            assertEquals(403,client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/ping")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200,client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/actuator/health/readiness")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class IndexTestConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        dev.traceback.indexing.EmbeddingProvider testEmbeddings() {
            return new dev.traceback.indexing.EmbeddingProvider() {
                public List<float[]> documents(List<String> texts){return texts.stream().map(t->new float[]{1,0,0}).toList();}
                public float[] query(String text){return new float[]{1,0,0};}
            };
        }
    }
    @Test void indexingEventUsesActualSharedSqsDispatcherConsumerAndDurableAcknowledgement() {
        var doc=upload();
        try(var context=new SpringApplicationBuilder(TracebackBackendApplication.class,IndexTestConfiguration.class).profiles("worker").run(
            "--server.port=0","--spring.datasource.url="+postgres.getJdbcUrl(),"--spring.datasource.username="+postgres.getUsername(),
            "--spring.datasource.password="+postgres.getPassword(),"--traceback.documents.storage-directory="+ROOT,
            "--traceback.aws.endpoint=http://localhost:"+aws.getMappedPort(4566),"--traceback.ingestion.scheduling-enabled=false",
            "--traceback.indexing.enabled=true","--traceback.embedding.dimensions=3","--traceback.embedding.digest=sha256:"+"1".repeat(64))) {
            var dispatch=context.getBean(OutboxDispatcher.class);var consume=context.getBean(QueueConsumer.class);
            assertTrue(dispatch.dispatchOne());consume.pollOnce();assertEquals("VERIFIED",job(doc).status());
            UUID generation=jobs.events().stream().filter(e->e.indexGeneration()!=null).findFirst().orElseThrow().indexGeneration();
            assertEquals("PENDING_EXTRACTION",context.getBean(dev.traceback.indexing.IndexRepository.class).find(generation,false).status());
            assertTrue(dispatch.dispatchOne());consume.pollOnce();
            assertEquals("INDEXED",context.getBean(dev.traceback.indexing.IndexRepository.class).find(generation,false).status());
            assertTrue(receive(queueUrl).isEmpty());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM document_index_heads",Integer.class));
            assertEquals(0,jobs.pendingEvents());
        }
    }
}
