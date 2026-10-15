package dev.traceback.deployment;

import dev.traceback.knowledge.*;
import dev.traceback.organization.OrganizationService;
import dev.traceback.organization.dto.CreateOrganizationRequest;
import dev.traceback.ingestion.IngestionRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Actual pg_dump/pg_restore, only inside a freshly allocated disposable container. */
@Testcontainers
@SpringBootTest(properties = "traceback.ingestion.scheduling-enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RestoreIntegrationTest {
    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword); r.add("traceback.documents.storage-directory", TestDocumentStorage::directory);
    }
    @MockitoBean JwtDecoder decoder;
    @Autowired DocumentService documents;
    @Autowired OrganizationService organizations;
    @Autowired JdbcTemplate jdbc;
    @Autowired IngestionRepository jobs;
    @Autowired DocumentUploadRecovery recovery;
    @Autowired javax.sql.DataSource datasource;
    @Autowired dev.traceback.knowledge.storage.DocumentStorage storage;
    @Autowired PlatformTransactionManager manager;
    @Autowired DocumentRuntimeHealth health;
    @Autowired dev.traceback.ingestion.IngestionSettings ingestionSettings;
    @Autowired dev.traceback.indexing.IndexSettings indexSettings;
    @Autowired DocumentUploadValidator validator;

    @Test void runtimeRolesCannotChangeSchemaAndWorkersCannotReadPrivateChatOrHumanIdentities() throws Exception {
        postgres.copyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath("../scripts/deployment/grants.sql"), "/tmp/grants.sql");
        assertEquals(0, postgres.execInContainer("psql", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(), "-c",
                "CREATE ROLE m17_api LOGIN; CREATE ROLE m17_worker LOGIN;").getExitCode());
        assertEquals(0, postgres.execInContainer("psql", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(), "-v", "ON_ERROR_STOP=1",
                "-v", "app_role=m17_api", "-v", "worker_role=m17_worker", "-f", "/tmp/grants.sql").getExitCode());
        var human = Jwt.withTokenValue("synthetic").header("alg", "RS256").issuer("https://issuer.example.test")
                .subject("grant-fixture").claim("traceback_principal_type", "human").build();
        var org = organizations.create(human, new CreateOrganizationRequest("Role fixture")).id();
        var doc = documents.upload(human, org, "Worker role", new MockMultipartFile("file", "role.md", "text/markdown", "Synthetic worker role verification".getBytes(StandardCharsets.UTF_8)));
        var event = jobs.events().stream().filter(e -> e.message().document().equals(doc.id())).findFirst().orElseThrow();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); var s = connection.createStatement()) {
            s.execute("SET ROLE m17_worker");
            assertDoesNotThrow(() -> s.executeQuery("SELECT count(*) FROM ingestion_jobs"));
            assertDoesNotThrow(() -> s.executeQuery("SELECT id FROM documents FOR UPDATE"));
            for (String table : new String[]{"app_users", "organization_memberships", "incidents", "conversation_messages"})
                assertThrows(java.sql.SQLException.class, () -> s.executeQuery("SELECT count(*) FROM " + table));
            assertThrows(java.sql.SQLException.class, () -> s.executeUpdate("UPDATE document_revisions SET status='FAILED'"));
            assertThrows(java.sql.SQLException.class, () -> s.execute("CREATE TABLE forbidden(id integer)"));
            s.execute("RESET ROLE"); s.execute("SET ROLE m17_api");
            assertDoesNotThrow(() -> s.executeQuery("SELECT count(*) FROM conversations"));
            assertThrows(java.sql.SQLException.class, () -> s.execute("CREATE TABLE forbidden(id integer)"));
            assertThrows(java.sql.SQLException.class, () -> s.executeUpdate("DELETE FROM document_revisions"));
            s.execute("RESET ROLE"); s.execute("SET ROLE m17_worker");
            var roleDataSource = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
            assertDoesNotThrow(() -> DatabaseMigration.validateRuntime(roleDataSource));
            var roleJdbc = new JdbcTemplate(roleDataSource);
            var roleTransactions = new org.springframework.jdbc.datasource.DataSourceTransactionManager(roleDataSource);
            var roleIndexes = new dev.traceback.indexing.IndexRepository(roleJdbc, roleTransactions, indexSettings);
            var roleJobs = new IngestionRepository(roleJdbc, roleTransactions, ingestionSettings, roleIndexes);
            var worker = new dev.traceback.ingestion.IngestionWorker(roleJobs, storage, validator, ingestionSettings, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            assertTrue(worker.handle(event.message().encode()).acknowledge());
            assertEquals("VERIFIED", roleJobs.find(org, doc.id(), doc.currentRevision().id(), false).status());
        }
    }

    @Test void restoresImmutableSourceMetadataMigrationsAndUnfinishedJobsAndFailsWithoutLegacyStorage() throws Exception {
        var human = Jwt.withTokenValue("synthetic").header("alg", "RS256").issuer("https://issuer.example.test")
                .subject("restore-fixture").claim("traceback_principal_type", "human").build();
        UUID org = organizations.create(human, new CreateOrganizationRequest("Restore fixture")).id();
        byte[] content = "# Recovery\nSynthetic restore evidence.".getBytes(StandardCharsets.UTF_8);
        var document = documents.upload(human, org, "Recovery", new MockMultipartFile("file", "recovery.md", "text/markdown", content));
        var event = jobs.events().getFirst();
        assertEquals("CLAIMED", jobs.claim(event.message()).outcome());
        jdbc.update("UPDATE ingestion_jobs SET lease_until=clock_timestamp()-interval '1 second'");
        var noLegacy = new DocumentUploadRecovery(jdbc, datasource, storage, manager);
        ReflectionTestUtils.setField(noLegacy, "runtime", "worker");
        ReflectionTestUtils.setField(noLegacy, "awsEnabled", true);
        ReflectionTestUtils.setField(noLegacy, "legacyEnabled", false);
        assertThrows(IllegalStateException.class, noLegacy::start);
        assertEquals(0, postgres.execInContainer("pg_dump", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(), "-Fc", "-f", "/tmp/m17.dump").getExitCode());
        assertEquals(0, postgres.execInContainer("createdb", "-U", postgres.getUsername(), "m17_restored").getExitCode());
        assertEquals(0, postgres.execInContainer("pg_restore", "-U", postgres.getUsername(), "--exit-on-error", "-d", "m17_restored", "/tmp/m17.dump").getExitCode());
        String restored = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/m17_restored");
        var flyway = Flyway.configure().dataSource(restored, postgres.getUsername(), postgres.getPassword()).load();
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(0, postgres.execInContainer("createdb", "-U", postgres.getUsername(), "m17_unmigrated").getExitCode());
        try (var empty = DriverManager.getConnection(postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/m17_unmigrated"), postgres.getUsername(), postgres.getPassword())) {
            assertThrows(RuntimeException.class, () -> DatabaseMigration.validateRuntime(new org.springframework.jdbc.datasource.SingleConnectionDataSource(empty, true)));
        }
        try (var connection = DriverManager.getConnection(restored, postgres.getUsername(), postgres.getPassword()); var statement = connection.createStatement()) {
            try (var r = statement.executeQuery("SELECT storage_provider,checksum FROM document_revisions")) {
                assertTrue(r.next()); assertEquals("LOCAL", r.getString(1)); assertEquals(DocumentUploadValidator.checksum(content), r.getString(2)); assertFalse(r.next());
            }
            try (var r = statement.executeQuery("SELECT count(*) FROM ingestion_jobs WHERE status='RUNNING' AND lease_until<clock_timestamp()")) {
                r.next(); assertEquals(1, r.getInt(1));
            }
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("UPDATE document_revisions SET storage_provider='S3'"));
        }
        assertArrayEquals(content, documents.download(human, org, document.id(), document.currentRevision().id()).bytes());
        assertEquals("UP", health.health().getStatus().getCode());
        recovery.close();
        try { assertEquals("DOWN", health.health().getStatus().getCode()); }
        finally { recovery.start(); }
        // Restore preserves expired ownership; reclaim under a fresh token, never reuse the old worker.
        assertEquals("CLAIMED", jobs.claim(event.message()).outcome());
    }
}
