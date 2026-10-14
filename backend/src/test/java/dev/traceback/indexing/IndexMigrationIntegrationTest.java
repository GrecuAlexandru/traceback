package dev.traceback.indexing;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
@Testcontainers
class IndexMigrationIntegrationTest {
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Test void v6UpgradePreservesExistingDataAndRejectsForeignOwnershipAndPartialPublication() {
        var config=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration");
        config.target("6").load().migrate();var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        UUID org=UUID.randomUUID(),foreign=UUID.randomUUID(),user=UUID.randomUUID(),doc=UUID.randomUUID(),rev=UUID.randomUUID(),gen=UUID.randomUUID();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?,'A'),(?,'B')",org,foreign);
        jdbc.update("INSERT INTO app_users(id,issuer,subject) VALUES (?,'https://issuer.example','alice')",user);
        jdbc.update("INSERT INTO documents(id,organization_id,title,created_by,status) VALUES (?,?,'Preserved',?,'UPLOADING')",doc,org,user);
        jdbc.update("INSERT INTO document_revisions(id,organization_id,document_id,revision_number,original_filename,media_type,file_size,checksum,storage_key,uploaded_by,status) VALUES (?,?,?,1,'runbook.txt','text/plain',6,?,?,?,'AVAILABLE')",rev,org,doc,"0".repeat(64),UUID.randomUUID(),user);
        jdbc.update("UPDATE documents SET status='AVAILABLE',current_revision_id=? WHERE id=?",rev,doc);
        UUID job=UUID.randomUUID(),event=UUID.randomUUID();
        jdbc.update("INSERT INTO ingestion_jobs(id,organization_id,document_id,revision_id) VALUES (?,?,?,?)",job,org,doc,rev);
        jdbc.update("INSERT INTO ingestion_outbox(id,organization_id,document_id,revision_id,job_id,generation) VALUES (?,?,?,?,?,0)",event,org,doc,rev,job);
        String before=jdbc.queryForObject("SELECT row_to_json(r)::text FROM document_revisions r",String.class);
        String jobBefore=jdbc.queryForObject("SELECT to_jsonb(j)::text FROM ingestion_jobs j",String.class);
        String eventBefore=jdbc.queryForObject("SELECT to_jsonb(o)::text FROM ingestion_outbox o",String.class);
        var latest=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration").target("7").load();
        assertEquals(1,latest.migrate().migrationsExecuted);latest.validate();assertEquals(before,jdbc.queryForObject("SELECT row_to_json(r)::text FROM document_revisions r",String.class));
        assertEquals(jobBefore,jdbc.queryForObject("SELECT to_jsonb(j)::text FROM ingestion_jobs j",String.class));
        assertEquals(eventBefore,jdbc.queryForObject("SELECT (to_jsonb(o)-'event_type'-'index_generation_id')::text FROM ingestion_outbox o",String.class));
        assertEquals("VERIFY",jdbc.queryForObject("SELECT event_type FROM ingestion_outbox",String.class));
        String id="1".repeat(64);
        jdbc.update("INSERT INTO index_configurations(id,model,model_digest,dimensions,extractor_version,chunker_version,chunk_size,overlap,max_chunks,max_text,max_pages) VALUES (?,'local',?,768,'v1','v1',1600,160,512,1000000,200)",id,"sha256:"+"1".repeat(64));
        assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO index_generations(id,organization_id,document_id,revision_id,configuration_id) VALUES (?,?,?,?,?)",gen,foreign,doc,rev,id));
        jdbc.update("INSERT INTO index_generations(id,organization_id,document_id,revision_id,configuration_id) VALUES (?,?,?,?,?)",gen,org,doc,rev,id);
        assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO document_index_heads(organization_id,document_id,revision_id,generation_id,configuration_id) VALUES (?,?,?,?,?)",org,doc,rev,gen,id));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE index_generations SET status='INDEXING' WHERE id=?",gen));
        assertEquals(0,latest.migrate().migrationsExecuted);
    }
}
