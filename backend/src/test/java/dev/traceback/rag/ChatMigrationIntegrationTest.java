package dev.traceback.rag;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ChatMigrationIntegrationTest {
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Test void appendOnlyV8PreservesSourcesAndEnforcesTenantMessageAndTurnConstraints() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration").target("7").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        UUID org=UUID.randomUUID(),other=UUID.randomUUID(),user=UUID.randomUUID(),doc=UUID.randomUUID(),rev=UUID.randomUUID();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?,'A'),(?,'B')",org,other);jdbc.update("INSERT INTO app_users(id,issuer,subject) VALUES (?,'https://issuer.example','alice')",user);
        jdbc.update("INSERT INTO documents(id,organization_id,title,created_by,status) VALUES (?,?,'Preserved',?,'UPLOADING')",doc,org,user);
        jdbc.update("INSERT INTO document_revisions(id,organization_id,document_id,revision_number,original_filename,media_type,file_size,checksum,storage_key,uploaded_by,status) VALUES (?,?,?,1,'runbook.txt','text/plain',6,?,?,?,'AVAILABLE')",rev,org,doc,"0".repeat(64),UUID.randomUUID(),user);
        jdbc.update("UPDATE documents SET status='AVAILABLE',current_revision_id=? WHERE id=?",rev,doc);
        String before=jdbc.queryForObject("SELECT to_jsonb(d)::text FROM documents d",String.class),revisionBefore=jdbc.queryForObject("SELECT to_jsonb(r)::text FROM document_revisions r",String.class);
        var latest=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration").load();assertEquals(1,latest.migrate().migrationsExecuted);latest.validate();assertEquals(0,latest.migrate().migrationsExecuted);
        assertEquals(before,jdbc.queryForObject("SELECT to_jsonb(d)::text FROM documents d",String.class));assertEquals(revisionBefore,jdbc.queryForObject("SELECT to_jsonb(r)::text FROM document_revisions r",String.class));
        UUID conversation=UUID.randomUUID(),turn=UUID.randomUUID(),key=UUID.randomUUID();jdbc.update("INSERT INTO conversations(id,organization_id,owner_id,title) VALUES (?,?,?,'Private')",conversation,org,user);
        String insert="INSERT INTO conversation_turns(id,organization_id,conversation_id,request_key,request_hash,status,deadline) VALUES (?,?,?,?,'"+"1".repeat(64)+"','GENERATING',clock_timestamp()+interval '1 minute')";
        assertThrows(DataAccessException.class,()->jdbc.update(insert,UUID.randomUUID(),other,conversation,UUID.randomUUID()));jdbc.update(insert,turn,org,conversation,key);
        assertThrows(DataAccessException.class,()->jdbc.update(insert,UUID.randomUUID(),org,conversation,UUID.randomUUID()));
        assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO conversation_messages(id,organization_id,conversation_id,turn_id,sequence,role,content) VALUES (?,?,?,?,0,'USER','Hello')",UUID.randomUUID(),other,conversation,turn));
        jdbc.update("INSERT INTO conversation_messages(id,organization_id,conversation_id,turn_id,sequence,role,content) VALUES (?,?,?,?,0,'USER','Hello')",UUID.randomUUID(),org,conversation,turn);
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE conversation_turns SET status='ANSWERED' WHERE id=?",turn));
    }
}
