package dev.traceback.knowledge;

import dev.traceback.knowledge.storage.DocumentStorage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Internal maintenance only: no caller-selectable recovery paths or public storage keys. */
@Component
@DependsOnDatabaseInitialization
public class DocumentUploadRecovery {
    private final JdbcTemplate jdbc;
    private final DataSource datasource;
    private final DocumentStorage storage;
    private final TransactionTemplate transaction;
    private Connection runtimeLock;
    @org.springframework.beans.factory.annotation.Value("${traceback.runtime:api}")
    private String runtime;
    @org.springframework.beans.factory.annotation.Value("${traceback.aws.enabled:false}")
    private boolean awsEnabled;
    @org.springframework.beans.factory.annotation.Value("${traceback.documents.legacy-enabled:true}")
    private boolean legacyEnabled;
    @org.springframework.beans.factory.annotation.Value("${traceback.deployment.validate-schema:false}")
    private boolean validateSchema;

    public DocumentUploadRecovery(JdbcTemplate jdbc, DataSource datasource, DocumentStorage storage, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.datasource = datasource;
        this.storage = storage;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @PostConstruct
    public void start() throws SQLException, IOException {
        if (validateSchema) dev.traceback.deployment.DatabaseMigration.validateRuntime(datasource);
        if (!legacyEnabled && jdbc.queryForObject("SELECT count(*) FROM document_revisions WHERE storage_provider='LOCAL'", Long.class) > 0)
            throw new IllegalStateException("LOCAL revisions require the original private storage mount");
        if ("worker".equals(runtime)) {
            if (!awsEnabled) throw new IllegalStateException("Worker requires AWS configuration");
            return;
        }
        // M10 is intentionally a single backend process per database. A session lock
        // prevents startup recovery from cancelling another process's live upload.
        runtimeLock = datasource.getConnection();
        try (var statement = runtimeLock.createStatement(); var result = statement.executeQuery("SELECT pg_try_advisory_lock(780102026)")) {
            result.next();
            if (!result.getBoolean(1)) {
                runtimeLock.close();
                runtimeLock = null;
                throw new SQLException("Document runtime is already active for this database");
            }
        }
        try { recoverInterruptedUploads(); }
        catch (RuntimeException | IOException ex) { close(); throw ex; }
    }

    public void requireActiveRuntime() {
        try {
            if (runtimeLock == null || !runtimeLock.isValid(2)) throw DocumentException.storage();
        } catch (SQLException ex) { throw DocumentException.storage(); }
    }

    // Package-private so integration tests can simulate a restart after an abandoned attempt.
    void recoverInterruptedUploads() throws IOException {
        var attempts = jdbc.query("SELECT organization_id, document_id, id FROM document_revisions WHERE status = 'UPLOADING'",
                (r, n) -> new UUID[]{r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class)});
        for (var attempt : attempts) fail(attempt[0], attempt[1], attempt[2]);
        var keys = jdbc.query("SELECT storage_provider, storage_key FROM document_revisions WHERE status = 'FAILED'", (r, n) -> new StorageObject(r.getString(1), r.getObject(2, UUID.class)));
        // Fail startup if reconciliation cannot finish; do not advertise a healthy runtime.
        for (var object : keys) storage.delete(object.provider(), object.key());
    }

    public boolean fail(UUID org, UUID document, UUID revision) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            jdbc.queryForObject("SELECT id FROM documents WHERE organization_id = ? AND id = ? FOR UPDATE", UUID.class, org, document);
            int changed = jdbc.update("UPDATE document_revisions SET status = 'FAILED' WHERE organization_id = ? AND document_id = ? AND id = ? AND status = 'UPLOADING'", org, document, revision);
            if (changed == 1) jdbc.update("""
                    UPDATE documents SET status = CASE WHEN current_revision_id IS NULL THEN 'FAILED' ELSE status END,
                    version = version + 1, modified_at = clock_timestamp() WHERE organization_id = ? AND id = ?
                    """, org, document);
            return changed == 1;
        }));
    }

    private record StorageObject(String provider, UUID key) {}

    @PreDestroy
    public void close() throws SQLException {
        if (runtimeLock != null) {
            // A pooled Connection.close() may retain the server session; unlock explicitly.
            try (var statement = runtimeLock.createStatement()) { statement.execute("SELECT pg_advisory_unlock(780102026)"); }
            finally { runtimeLock.close(); runtimeLock = null; }
        }
    }
}
