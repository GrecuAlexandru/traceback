package dev.traceback.knowledge;

import com.jayway.jsonpath.JsonPath;
import dev.traceback.knowledge.dto.UpdateDocumentRequest;
import dev.traceback.knowledge.storage.DocumentStorage;
import dev.traceback.organization.OrganizationPermissionDeniedException;
import dev.traceback.security.HumanIdentityPolicy;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@AutoConfigureMockMvc
class DocumentApiIntegrationTest {
    static final String ISSUER = "http://localhost:8180/realms/traceback";
    static final String ROOT = TestDocumentStorage.directory();
    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("traceback.documents.storage-directory", () -> ROOT);
        r.add("traceback.documents.max-file-size-bytes", () -> 4096);
    }
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean DocumentStorage storage;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentService service;
    @Autowired DocumentRepository repository;
    @Autowired DocumentUploadRecovery recovery;
    @Autowired DocumentUploadValidator validator;
    @Autowired PlatformTransactionManager manager;
    UUID orgA, orgB;

    @BeforeEach void setup() throws Exception {
        reset(storage);
        // Disposable database only. Break the current-revision FK before deleting fixtures.
        jdbc.update("UPDATE documents SET current_revision_id = NULL, status = 'DELETED'");
        jdbc.update("DELETE FROM ingestion_outbox");
        jdbc.update("DELETE FROM ingestion_jobs");
        jdbc.update("DELETE FROM document_revisions");
        jdbc.update("DELETE FROM documents");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM organization_memberships");
        jdbc.update("DELETE FROM organizations");
        jdbc.update("DELETE FROM app_users");
        try (var files = Files.list(Path.of(ROOT))) {
            for (Path file : files.filter(p -> !p.getFileName().toString().startsWith(".")).toList()) Files.delete(file);
        }
        orgA = organization("A"); orgB = organization("B");
        member("alice", orgA, "ADMIN"); member("engineer", orgA, "ENGINEER"); member("viewer", orgA, "VIEWER");
        member("bob", orgB, "ADMIN");
    }
    UUID organization(String name) {
        UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO organizations(id,name) VALUES (?,?)", id, name); return id;
    }
    void member(String subject, UUID org, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users(id,issuer,subject) VALUES (?,?,?) ON CONFLICT (issuer,subject) DO NOTHING", id, ISSUER, subject);
        id = jdbc.queryForObject("SELECT id FROM app_users WHERE issuer=? AND subject=?", UUID.class, ISSUER, subject);
        jdbc.update("INSERT INTO organization_memberships(id,organization_id,user_id,role) VALUES (?,?,?,?)", UUID.randomUUID(), org, id, role);
    }
    RequestPostProcessor human(String subject) { return jwt().jwt(t -> t.issuer(ISSUER).subject(subject).claim("traceback_principal_type", "human")); }
    Jwt token(String subject) { return Jwt.withTokenValue("synthetic").header("alg","RS256").issuer(ISSUER).subject(subject).claim("traceback_principal_type","human").build(); }
    HumanIdentityPolicy.Identity identity(String subject) { return new HumanIdentityPolicy.Identity(ISSUER, subject); }
    String collection(UUID org) { return "/api/v1/organizations/" + org + "/documents"; }
    String item(UUID id) { return collection(orgA) + "/" + id; }
    MockMultipartFile text(String text) { return file("runbook.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8)); }
    MockMultipartFile file(String name, String media, byte[] bytes) { return new MockMultipartFile("file", name, media, bytes); }
    dev.traceback.knowledge.dto.DocumentResponse upload() { return service.upload(token("alice"), orgA, "Runbook", text("original")); }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    long files() throws IOException { try (var files = Files.list(Path.of(ROOT))) { return files.filter(p -> !p.getFileName().toString().startsWith(".")).count(); } }
    byte[] pdf(boolean withText) throws IOException {
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(); doc.addPage(page);
            if (withText) {
                // Construct a standard-font fixture without invoking the host font scanner/cache.
                var font = new COSDictionary();
                font.setItem(COSName.TYPE, COSName.FONT); font.setItem(COSName.SUBTYPE, COSName.TYPE1);
                font.setItem(COSName.BASE_FONT, COSName.getPDFName("Helvetica"));
                var fonts = new COSDictionary(); fonts.setItem(COSName.getPDFName("F1"), font);
                var resources = new COSDictionary(); resources.setItem(COSName.FONT, fonts);
                page.setResources(new PDResources(resources));
                var stream = new PDStream(doc);
                try (var content = stream.createOutputStream()) {
                    content.write("BT /F1 12 Tf 20 700 Td (Runbook text) Tj ET".getBytes(StandardCharsets.US_ASCII));
                }
                page.setContents(stream);
            }
            doc.save(output); return output.toByteArray();
        }
    }

    @Test void supportedFormatsRoundTripWithTrustedMetadataChecksumAndSafeHeaders() throws Exception {
        for (MockMultipartFile file : List.of(text("Hello UTF-8 π"), file("guide.md", "text/markdown", "# Guide\ntext".getBytes(StandardCharsets.UTF_8)), file("guide.pdf", "application/pdf", pdf(true)))) {
            var response = mvc.perform(multipart(collection(orgA)).file(file).param("title", "  Guide  ").with(human("alice")))
                    .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.status").value("AVAILABLE")).andExpect(jsonPath("$.title").value("Guide"))
                    .andExpect(jsonPath("$.currentRevision.fileSize").value(file.getSize()))
                    .andExpect(jsonPath("$.currentRevision.checksum").value(DocumentUploadValidator.checksum(file.getBytes())))
                    .andExpect(jsonPath("$.currentRevision.storageKey").doesNotExist()).andReturn().getResponse().getContentAsString();
            UUID id = UUID.fromString(JsonPath.read(response, "$.id"));
            mvc.perform(get(item(id) + "/content").with(human("viewer"))).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                    .andExpect(content().bytes(file.getBytes()));
            assertFalse(response.contains(ROOT));
        }
        assertEquals(3, files());
    }

    @Test void invalidUploadsDoNotReserveMetadataOrFiles() throws Exception {
        var cases = List.of(file("bad.exe", "text/plain", new byte[]{1}), file("bad.md", "application/pdf", "text".getBytes()),
                file("bad.txt", "text/plain", pdf(true)), file("bad.pdf", "application/pdf", "%PDF-1.7\nbroken".getBytes()),
                text(""), text("   \n"), file("bad.txt", "text/plain", new byte[]{(byte)0xff}),
                file("bad.txt", "text/plain", new byte[]{0, 1}), file("empty.pdf", "application/pdf", pdf(false)),
                file("bad.txt", "application/octet-stream", "text".getBytes()), file("bad.txt", "text/plain;charset=ISO-8859-1", "text".getBytes()));
        for (var file : cases) mvc.perform(multipart(collection(orgA)).file(file).param("title", "Bad").with(human("alice")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        assertEquals(0, count("documents")); assertEquals(0, files());
    }

    @Test void oversizedContentIsBoundedEvenWithFalseDeclaredSize() throws Exception {
        mvc.perform(multipart(collection(orgA)).file(text("x".repeat(4097))).param("title", "Large").with(human("alice")))
                .andExpect(status().isPayloadTooLarge());
        var liar = new MockMultipartFile("file", "small.txt", "text/plain", "x".repeat(4097).getBytes()) {
            @Override public long getSize() { return 1; }
        };
        assertEquals(413, assertThrows(DocumentException.class, () -> service.upload(token("alice"), orgA, "Large", liar)).status().value());
        assertEquals(0, count("documents")); assertEquals(0, files());
    }

    @Test void pathTraversalControlCharactersAndUserPathsAreRejected() throws Exception {
        for (String name : List.of("../secret.txt", "/tmp/secret.txt", "C:\\secret.txt", "folder/guide.md", "bad\r\nheader.txt", "bad\u0000.txt")) {
            mvc.perform(multipart(collection(orgA)).file(file(name, "text/plain", "text".getBytes())).param("title", "Bad").with(human("alice")))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0, files());
        var doc = upload();
        UUID key = jdbc.queryForObject("SELECT storage_key FROM document_revisions", UUID.class);
        mvc.perform(get(item(doc.id()) + "/revisions/" + key + "/content").with(human("alice"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/" + key).with(human("alice"))).andExpect(status().isNotFound());
    }

    @Test void foreignKnownIdsAndRevisionIdsCannotCrossTenantOrDocumentBoundaries() throws Exception {
        var foreign = service.upload(token("bob"), orgB, "Private", text("private"));
        var own = upload();
        UUID id = foreign.id(), revision = foreign.currentRevision().id();
        for (String route : List.of(item(id), item(id) + "/content", item(id) + "/revisions", item(id) + "/revisions/" + revision + "/content", item(id) + "/revisions/" + revision + "/ingestion",
                item(own.id()) + "/revisions/" + revision + "/content")) {
            mvc.perform(get(route).with(human("alice"))).andExpect(status().isNotFound());
        }
        mvc.perform(multipart(item(id) + "/revisions").file(text("overwrite")).param("version","0").with(human("alice"))).andExpect(status().isNotFound());
        mvc.perform(delete(item(id)).param("version","0").with(human("alice"))).andExpect(status().isNotFound());
        mvc.perform(patch(item(id)).contentType("application/json").content("{\"version\":0,\"title\":\"Bad\"}").with(human("alice"))).andExpect(status().isNotFound());
        for (String route : List.of(collection(orgB), collection(orgB)+"/"+id, collection(orgB)+"/"+id+"/content"))
            mvc.perform(get(route).with(human("alice"))).andExpect(status().isForbidden());
        assertThrows(OrganizationPermissionDeniedException.class, () -> service.download(token("alice"), orgB, id, revision));
    }

    @Test void viewerReadsButCannotUploadRevisePatchOrDelete() throws Exception {
        var doc = upload();
        for (String route : List.of(collection(orgA), item(doc.id()), item(doc.id())+"/revisions", item(doc.id())+"/content"))
            mvc.perform(get(route).with(human("viewer"))).andExpect(status().isOk());
        mvc.perform(multipart(collection(orgA)).file(text("new")).param("title","Denied").with(human("viewer"))).andExpect(status().isForbidden());
        mvc.perform(multipart(item(doc.id())+"/revisions").file(text("new")).param("version", "2").with(human("viewer"))).andExpect(status().isForbidden());
        mvc.perform(patch(item(doc.id())).contentType("application/json").content("{\"version\":2,\"title\":\"Denied\"}").with(human("viewer"))).andExpect(status().isForbidden());
        mvc.perform(delete(item(doc.id())).param("version","2").with(human("viewer"))).andExpect(status().isForbidden());
        assertEquals(1, count("document_revisions"));
    }

    @Test void engineerCanPerformAllWrites() throws Exception {
        var doc = service.upload(token("engineer"), orgA, "Engineer", text("one"));
        doc = service.revise(token("engineer"), orgA, doc.id(), doc.version(), text("two"));
        doc = service.update(token("engineer"), orgA, doc.id(), new UpdateDocumentRequest(doc.version(), "Edited"));
        service.delete(token("engineer"), orgA, doc.id(), doc.version());
        assertEquals("DELETED", jdbc.queryForObject("SELECT status FROM documents", String.class));
    }

    @Test void anonymousAndMachineClientsCannotReadOrWriteEvenWithSpoofedRoles() throws Exception {
        var doc = upload();
        for (String route : List.of(collection(orgA), item(doc.id()), item(doc.id())+"/content", item(doc.id())+"/revisions")) {
            mvc.perform(get(route)).andExpect(status().isUnauthorized());
            mvc.perform(get(route).with(jwt().jwt(t -> t.issuer(ISSUER).subject("alice").claim("roles", List.of("ADMIN")))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(multipart(collection(orgA)).file(text("new")).param("title","Denied")).andExpect(status().isUnauthorized());
        mvc.perform(multipart(collection(orgA)).file(text("new")).param("title","Denied").with(jwt()))
                .andExpect(status().isForbidden());
        assertEquals(1, count("documents"));
    }

    @Test void roleRevocationTakesEffectWithoutReissuingTokenAndRepositoryScopesIndependently() {
        var doc = upload();
        assertThrows(DocumentException.class, () -> repository.find(orgA, doc.id(), identity("bob"), false));
        assertEquals(0, repository.count(orgA, identity("bob"), DocumentStatus.AVAILABLE));
        assertThrows(DocumentException.class, () -> repository.revision(orgB, doc.id(), doc.currentRevision().id(), identity("bob")));
        new TransactionTemplate(manager).executeWithoutResult(tx -> assertThrows(OrganizationPermissionDeniedException.class,
                () -> repository.touch(orgA, doc.id(), identity("viewer"), "Denied", doc.version(), false)));
        jdbc.update("DELETE FROM organization_memberships WHERE user_id = (SELECT id FROM app_users WHERE subject='alice')");
        assertThrows(OrganizationPermissionDeniedException.class, () -> service.download(token("alice"), orgA, doc.id(), null));
    }

    @Test void immutableHistoryKeepsPriorContentAndSeparatesRevisionNumbersFromLockVersion() throws Exception {
        var first = upload();
        var updated = service.revise(token("alice"), orgA, first.id(), first.version(), text("second"));
        assertEquals(first.id(), updated.id()); assertEquals(2, updated.currentRevision().revisionNumber());
        assertEquals(first.version()+2, updated.version());
        assertArrayEquals("original".getBytes(), service.download(token("alice"), orgA, first.id(), first.currentRevision().id()).bytes());
        assertArrayEquals("second".getBytes(), service.download(token("alice"), orgA, first.id(), null).bytes());
        mvc.perform(get(item(first.id())+"/revisions").with(human("viewer"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.content[0].revisionNumber").value(2));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("UPDATE document_revisions SET checksum=? WHERE id=?", "0".repeat(64), first.currentRevision().id()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("UPDATE document_revisions SET status='FAILED' WHERE id=?", first.currentRevision().id()));
    }

    @Test void metadataPatchDoesNotAlterHistoryAndRejectsStaleVersions() throws Exception {
        var doc = upload();
        var edited = service.update(token("alice"), orgA, doc.id(), new UpdateDocumentRequest(doc.version(), "Renamed"));
        assertEquals(doc.currentRevision(), edited.currentRevision()); assertEquals(doc.version()+1, edited.version());
        assertEquals(edited.version(), service.update(token("alice"), orgA, doc.id(), new UpdateDocumentRequest(edited.version(), "Renamed")).version());
        mvc.perform(patch(item(doc.id())).with(human("alice")).contentType("application/json").content("{\"version\":"+doc.version()+",\"title\":\"Stale\"}"))
                .andExpect(status().isConflict());
        assertEquals(409, assertThrows(DocumentException.class, () -> service.revise(token("alice"), orgA, doc.id(), doc.version(), text("stale"))).status().value());
        mvc.perform(delete(item(doc.id())).with(human("alice")).param("version", "0")).andExpect(status().isConflict());
        assertEquals(1, count("document_revisions"));
    }

    @Test void paginationAndStatusFilteringUseDeterministicTenantScopedCounts() throws Exception {
        for (int i=0; i<4; i++) upload();
        service.upload(token("bob"), orgB, "Private", text("private"));
        jdbc.update("UPDATE documents SET created_at='2026-01-01T00:00:00Z' WHERE organization_id=?", orgA);
        var all = service.list(token("alice"), orgA, null, 0, 100);
        assertEquals(4, all.totalElements());
        var first = service.list(token("alice"), orgA, null, 0, 2);
        var second = service.list(token("alice"), orgA, null, 1, 2);
        assertEquals(all.content(), java.util.stream.Stream.concat(first.content().stream(), second.content().stream()).toList());
        assertEquals(2, first.totalPages());
        var deleted = all.content().getFirst(); service.delete(token("alice"), orgA, deleted.id(), deleted.version());
        mvc.perform(get(collection(orgA)).with(human("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get(collection(orgA)).with(human("alice")).param("status","DELETED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].currentRevision").isEmpty());
        mvc.perform(get(collection(orgA)).with(human("alice")).param("page","-1")).andExpect(status().isBadRequest());
        mvc.perform(get(collection(orgA)).with(human("alice")).param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get(collection(orgA)).with(human("alice")).param("status","WRONG")).andExpect(status().isBadRequest());
    }

    @Test void validationMissingPartsTitlesAndVersionsReturnStructuredErrors() throws Exception {
        for (String title : List.of("", " ", "x".repeat(201)))
            mvc.perform(multipart(collection(orgA)).file(text("text")).param("title",title).with(human("alice"))).andExpect(status().isBadRequest());
        mvc.perform(multipart(collection(orgA)).param("title","Guide").with(human("alice"))).andExpect(status().isBadRequest());
        var doc = upload();
        for (String body : List.of("{}", "{\"version\":-1,\"title\":\"x\"}", "{\"version\":2,\"title\":\" \"}"))
            mvc.perform(patch(item(doc.id())).contentType("application/json").content(body).with(human("alice"))).andExpect(status().isBadRequest());
        mvc.perform(delete(item(doc.id())).with(human("alice"))).andExpect(status().isBadRequest());
        mvc.perform(delete(item(doc.id())).param("version","-1").with(human("alice"))).andExpect(status().isBadRequest());
    }

    @Test void deleteIsIdempotentAndBlocksCurrentAndHistoricalDownloadsWithoutErasingHistory() throws Exception {
        var first = upload(); var doc = service.revise(token("alice"), orgA, first.id(), first.version(), text("next"));
        for (int i=0;i<2;i++) mvc.perform(delete(item(doc.id())).param("version",Long.toString(doc.version())).with(human("alice"))).andExpect(status().isNoContent());
        for (String route : List.of(item(doc.id()), item(doc.id())+"/content", item(doc.id())+"/revisions",
                item(doc.id())+"/revisions/"+first.currentRevision().id()+"/content"))
            mvc.perform(get(route).with(human("alice"))).andExpect(status().isNotFound());
        assertEquals(2, count("document_revisions")); assertEquals(2, files());
        assertEquals(404, assertThrows(DocumentException.class, () -> service.revise(token("alice"), orgA, doc.id(), doc.version(), text("resurrect"))).status().value());
    }

    @Test void writeFailureAfterPartialObjectCleansFilesAndLeavesInaccessibleFailedAttempt() throws Exception {
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IOException("injected after write"); }).when(storage).put(any(), any());
        mvc.perform(multipart(collection(orgA)).file(text("failure")).param("title","Failed").with(human("alice")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value(503));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM documents", String.class));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM document_revisions", String.class));
        assertEquals(0, files());
        UUID id = jdbc.queryForObject("SELECT id FROM documents", UUID.class);
        mvc.perform(get(item(id)+"/content").with(human("alice"))).andExpect(status().isNotFound());
        reset(storage);
        var retried = service.revise(token("alice"), orgA, id, service.metadata(token("alice"), orgA, id).version(), text("retry"));
        assertEquals(2, retried.currentRevision().revisionNumber());
    }

    @Test void failedRevisionPreservesCurrentContentAndRecoverableCleanupRetries() throws Exception {
        var doc = upload();
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IOException("injected"); }).when(storage).put(any(), any());
        doThrow(new IOException("cleanup unavailable")).when(storage).delete(any());
        assertThrows(DocumentException.class, () -> service.revise(token("alice"), orgA, doc.id(), doc.version(), text("failed next")));
        assertArrayEquals("original".getBytes(), service.download(token("alice"), orgA, doc.id(), null).bytes());
        assertEquals(2, files());
        UUID failed = jdbc.queryForObject("SELECT id FROM document_revisions WHERE status='FAILED'", UUID.class);
        assertEquals(404, assertThrows(DocumentException.class, () -> service.download(token("alice"), orgA, doc.id(), failed)).status().value());
        reset(storage); recovery.recoverInterruptedUploads(); assertEquals(1, files());
    }

    @Test void finalizationFailureRollsBackPointerAndAvailabilityThenCleansObject() throws Exception {
        var original = upload();
        jdbc.execute("CREATE FUNCTION reject_test_publish() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='AVAILABLE' THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_test_publish BEFORE UPDATE ON document_revisions FOR EACH ROW EXECUTE FUNCTION reject_test_publish()");
        try {
            assertThrows(DocumentException.class, () -> service.revise(token("alice"), orgA, original.id(), original.version(), text("rollback")));
            assertEquals(original.currentRevision(), service.metadata(token("alice"), orgA, original.id()).currentRevision());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_revisions WHERE status='FAILED'", Integer.class));
            assertEquals(1, files());
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_publish ON document_revisions"); jdbc.execute("DROP FUNCTION reject_test_publish()");
        }
    }

    @Test void reservationFailureRollsBackDocumentBeforeAnyFileWrite() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_test_reserve() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_test_reserve BEFORE INSERT ON document_revisions FOR EACH ROW EXECUTE FUNCTION reject_test_reserve()");
        try {
            assertThrows(RuntimeException.class, this::upload);
            assertEquals(0, count("documents")); assertEquals(0, count("document_revisions")); assertEquals(0, files());
            verify(storage, never()).put(any(), any());
        } finally { jdbc.execute("DROP TRIGGER reject_test_reserve ON document_revisions"); jdbc.execute("DROP FUNCTION reject_test_reserve()"); }
    }

    @Test void startupRecoveryHandlesStagedAndPromotedInterruptedUploads() throws Exception {
        for (boolean promoted : List.of(false,true)) {
            var upload = validator.validate(text("abandoned")); UUID id = UUID.randomUUID();
            var attempt = new TransactionTemplate(manager).execute(tx -> {
                repository.insert(orgA, id, "Abandoned", identity("alice"));
                return repository.reserve(orgA, id, UUID.randomUUID(), UUID.randomUUID(), upload, identity("alice"));
            });
            if (promoted) storage.put(attempt.storageKey(), upload.bytes());
            else Files.write(Path.of(ROOT).resolve(attempt.storageKey()+".part"), upload.bytes());
            assertEquals(404, assertThrows(DocumentException.class, () -> service.download(token("alice"), orgA, id, attempt.metadata().id())).status().value());
        }
        recovery.recoverInterruptedUploads(); recovery.recoverInterruptedUploads();
        assertEquals(0, files()); assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM documents WHERE status='FAILED'", Integer.class));
    }

    @Test void concurrentRevisionRequestsHaveOneWinnerAndNoDuplicateOrOverwrittenHistory() throws Exception {
        var doc = upload(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls = new ArrayList<Future<Integer>>();
            for (String text : List.of("a", "b")) calls.add(pool.submit(() -> {
                start.await(5,TimeUnit.SECONDS);
                try { service.revise(token("alice"), orgA, doc.id(), doc.version(), text(text)); return 201; }
                catch (DocumentException ex) { return ex.status().value(); }
            }));
            start.countDown(); var statuses = new HashSet<Integer>();
            for (var call : calls) statuses.add(call.get(15, TimeUnit.SECONDS));
            assertEquals(Set.of(201,409), statuses);
        }
        assertEquals(2, count("document_revisions")); assertEquals(2, files());
        assertArrayEquals("original".getBytes(), service.download(token("alice"), orgA, doc.id(), doc.currentRevision().id()).bytes());
    }

    @Test void pendingUploadBlocksDeletionAndMetadataChangesAndRechecksAuthorizationBeforePublish() throws Exception {
        var doc = upload(); var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); assertTrue(proceed.await(10,TimeUnit.SECONDS)); return invocation.callRealMethod(); }).when(storage).put(any(),any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var call = pool.submit(() -> service.revise(token("alice"), orgA, doc.id(), doc.version(), text("next")));
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                var pending = service.metadata(token("alice"), orgA, doc.id());
                assertEquals(409, assertThrows(DocumentException.class, () -> service.delete(token("alice"), orgA, doc.id(), pending.version())).status().value());
                assertEquals(409, assertThrows(DocumentException.class, () -> service.update(token("alice"), orgA, doc.id(), new UpdateDocumentRequest(pending.version(),"Denied"))).status().value());
                jdbc.update("UPDATE organization_memberships SET role='VIEWER' WHERE user_id=(SELECT id FROM app_users WHERE subject='alice')");
            } finally { proceed.countDown(); }
            assertInstanceOf(OrganizationPermissionDeniedException.class, assertThrows(ExecutionException.class, () -> call.get(15,TimeUnit.SECONDS)).getCause());
        }
        assertEquals(1, files());
        assertEquals(doc.currentRevision(), service.metadata(token("alice"), orgA, doc.id()).currentRevision());
    }

    @Test void ingestionStatusUsesSameTenantAndMembershipBoundaryAsDownloads() throws Exception {
        var doc=upload();String path=item(doc.id())+"/revisions/"+doc.currentRevision().id()+"/ingestion";
        mvc.perform(get(path).with(human("viewer"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(human("bob"))).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM organization_memberships WHERE user_id=(SELECT id FROM app_users WHERE subject='viewer')");
        mvc.perform(get(path).with(human("viewer"))).andExpect(status().isForbidden());
        service.delete(token("alice"),orgA,doc.id(),doc.version());
        mvc.perform(get(path).with(human("alice"))).andExpect(status().isNotFound());
    }

    @Test void corruptOrMissingStorageNeverReturnsUnverifiedContent() throws Exception {
        var doc = upload(); UUID key = jdbc.queryForObject("SELECT storage_key FROM document_revisions", UUID.class);
        Files.writeString(Path.of(ROOT).resolve(key+".blob"), "tampered");
        mvc.perform(get(item(doc.id())+"/content").with(human("alice"))).andExpect(status().isServiceUnavailable());
        Files.delete(Path.of(ROOT).resolve(key+".blob"));
        mvc.perform(get(item(doc.id())+"/content").with(human("alice"))).andExpect(status().isServiceUnavailable());
    }

    @Test void compositeConstraintsRejectForeignRevisionAndCurrentPointerAndRestrictParentDeletion() {
        var own = upload(); var foreign = service.upload(token("bob"), orgB, "Foreign", text("private"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE documents SET current_revision_id=? WHERE id=?", foreign.currentRevision().id(), own.id()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM organizations WHERE id=?", orgA));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM app_users WHERE subject='alice'"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO document_revisions(id,organization_id,document_id,revision_number,original_filename,media_type,file_size,checksum,storage_key,uploaded_by,status)
                VALUES (?,?,?,2,'bad.txt','text/plain',1,?,?,?,'UPLOADING')
                """, UUID.randomUUID(), orgB, own.id(), "0".repeat(64), UUID.randomUUID(), own.createdBy()));
    }
    @Test void concurrentMetadataEditsUseTheSameOptimisticVersionBoundary() throws Exception {
        var doc = upload(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls = new ArrayList<Future<Integer>>();
            for (String title : List.of("First", "Second")) calls.add(pool.submit(() -> {
                start.await(5,TimeUnit.SECONDS);
                try { service.update(token("alice"), orgA, doc.id(), new UpdateDocumentRequest(doc.version(), title)); return 200; }
                catch (DocumentException ex) { return ex.status().value(); }
            }));
            start.countDown(); var statuses = new HashSet<Integer>();
            for (var call : calls) statuses.add(call.get(15,TimeUnit.SECONDS));
            assertEquals(Set.of(200,409),statuses);
        }
        assertEquals(doc.version()+1,service.metadata(token("alice"),orgA,doc.id()).version());
        assertEquals(1,count("document_revisions"));
    }

    @Test void committedAvailabilityIsNeverUndoneByFailureCompensation() throws Exception {
        var doc = upload();
        assertFalse(recovery.fail(orgA,doc.id(),doc.currentRevision().id()));
        assertEquals(doc,service.metadata(token("alice"),orgA,doc.id()));
        assertEquals(1,files());
    }

    @Test void explicitMachinePrincipalAndForeignOrganizationWritesFailClosed() throws Exception {
        var doc = upload();
        var machine = jwt().jwt(t -> t.issuer(ISSUER).subject("alice").claim("traceback_principal_type","service_account").claim("role","ADMIN"));
        mvc.perform(get(item(doc.id())+"/content").with(machine)).andExpect(status().isForbidden());
        mvc.perform(multipart(item(doc.id())+"/revisions").file(text("Denied")).param("version","2").with(machine)).andExpect(status().isForbidden());
        mvc.perform(delete(item(doc.id())).param("version","2").with(machine)).andExpect(status().isForbidden());
        mvc.perform(multipart(collection(orgB)).file(text("Denied")).param("title","Denied").with(human("alice"))).andExpect(status().isForbidden());
        mvc.perform(delete(collection(orgB)+"/"+doc.id()).param("version","2").with(human("alice"))).andExpect(status().isForbidden());
        assertEquals(1,count("document_revisions"));
    }

    @Test void realServletMultipartLimitReturns413WithoutReservingDocument() throws Exception {
        when(decoder.decode("synthetic-http-token")).thenReturn(token("alice"));
        String boundary = "traceback-test-boundary";
        String body = "--"+boundary+"\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nGuide\r\n"
                +"--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"guide.txt\"\r\nContent-Type: text/plain\r\n\r\n"
                +"x".repeat(4097)+"\r\n--"+boundary+"--\r\n";
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+collection(orgA)))
                .header("Authorization","Bearer synthetic-http-token")
                .header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).timeout(java.time.Duration.ofSeconds(10)).build();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var response = client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(413,response.statusCode(),response.body());
            assertTrue(response.body().contains("413"),response.body());
        }
        assertEquals(0,count("documents")); assertEquals(0,files());
    }

    @Test void downloadsHoldDocumentLockUntilContentIsReadBeforeDeletion() throws Exception {
        var doc = upload(); var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); assertTrue(proceed.await(10,TimeUnit.SECONDS)); return invocation.callRealMethod(); })
                .when(storage).read(any(),anyLong());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var download = pool.submit(() -> service.download(token("alice"),orgA,doc.id(),null));
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            var deletion = pool.submit(() -> { service.delete(token("alice"),orgA,doc.id(),doc.version()); return null; });
            try { assertThrows(TimeoutException.class, () -> deletion.get(150,TimeUnit.MILLISECONDS)); }
            finally { proceed.countDown(); }
            assertArrayEquals("original".getBytes(),download.get(10,TimeUnit.SECONDS).bytes());
            deletion.get(10,TimeUnit.SECONDS);
        }
        assertEquals(404,assertThrows(DocumentException.class, () -> service.download(token("alice"),orgA,doc.id(),null)).status().value());
    }

    @Test void encryptedPdfIsRejectedAndDatabaseRuntimeLockExcludesSecondSession() throws Exception {
        byte[] encrypted;
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf(true)); var output = new ByteArrayOutputStream()) {
            doc.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner","reader",
                    new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            doc.save(output); encrypted = output.toByteArray();
        }
        mvc.perform(multipart(collection(orgA)).file(file("encrypted.pdf","application/pdf",encrypted)).param("title","Encrypted").with(human("alice")))
                .andExpect(status().isBadRequest());
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
             var statement = connection.createStatement(); var result = statement.executeQuery("SELECT pg_try_advisory_lock(780102026)")) {
            result.next(); assertFalse(result.getBoolean(1));
        }
        assertEquals(0,count("documents")); assertEquals(0,files());
    }

}
