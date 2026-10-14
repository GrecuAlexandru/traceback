package dev.traceback.ingestion;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class IngestionMigrationIntegrationTest {
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine");
    @Test void v5UpgradePreservesImmutableLocalHistoryAndTenantConstraints() {
        var config=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration");
        config.target("5").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        UUID org=UUID.randomUUID(),foreign=UUID.randomUUID(),user=UUID.randomUUID(),doc=UUID.randomUUID(),rev=UUID.randomUUID(),key=UUID.randomUUID();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?,'A'),(?,'B')",org,foreign);
        jdbc.update("INSERT INTO app_users(id,issuer,subject) VALUES (?,'https://issuer.example','alice')",user);
        jdbc.update("INSERT INTO documents(id,organization_id,title,created_by,status) VALUES (?,?,'Preserved',?,'UPLOADING')",doc,org,user);
        jdbc.update("""
            INSERT INTO document_revisions(id,organization_id,document_id,revision_number,original_filename,media_type,file_size,checksum,storage_key,uploaded_by,status)
            VALUES (?,?,?,1,'runbook.txt','text/plain',6,?,?,?,'AVAILABLE')
            """,rev,org,doc,"0".repeat(64),key,user);
        jdbc.update("UPDATE documents SET status='AVAILABLE',current_revision_id=? WHERE id=?",rev,doc);
        var documentBefore=jdbc.queryForObject("SELECT row_to_json(d)::text FROM documents d",String.class);
        var revisionBefore=jdbc.queryForObject("SELECT to_jsonb(r)::text FROM document_revisions r",String.class);
        var latest=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration").load();
        assertEquals(1,latest.migrate().migrationsExecuted);latest.validate();
        assertEquals(documentBefore,jdbc.queryForObject("SELECT row_to_json(d)::text FROM documents d",String.class));
        assertEquals(revisionBefore,jdbc.queryForObject("SELECT (to_jsonb(r)-'storage_provider')::text FROM document_revisions r",String.class));
        assertEquals("LOCAL",jdbc.queryForObject("SELECT storage_provider FROM document_revisions",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ingestion_jobs",Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE document_revisions SET storage_provider='S3' WHERE id=?",rev));
        UUID job=UUID.randomUUID();
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO ingestion_jobs(id,organization_id,document_id,revision_id) VALUES (?,?,?,?)",job,foreign,doc,rev));
        jdbc.update("INSERT INTO ingestion_jobs(id,organization_id,document_id,revision_id) VALUES (?,?,?,?)",job,org,doc,rev);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO ingestion_jobs(id,organization_id,document_id,revision_id) VALUES (?,?,?,?)",UUID.randomUUID(),org,doc,rev));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO ingestion_outbox(id,organization_id,document_id,revision_id,job_id,generation) VALUES (?,?,?,?,?,0)",UUID.randomUUID(),foreign,doc,rev,job));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE ingestion_jobs SET status='RUNNING' WHERE id=?",job));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE ingestion_jobs SET status='VERIFIED' WHERE id=?",job));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE ingestion_jobs SET lease_token=? WHERE id=?",UUID.randomUUID(),job));
        assertEquals(0,latest.migrate().migrationsExecuted);
    }
}
