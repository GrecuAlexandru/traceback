package dev.traceback.knowledge;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class DocumentMigrationIntegrationTest {
    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    Flyway version(String target) {
        var config = Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }
    @Test void upgradeFromV4PreservesExistingIncidentsIdentitiesOrganizationsAndMemberships() throws Exception {
        version("3").migrate();
        try (var c = DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())) {
            UUID legacy = UUID.randomUUID(), org = UUID.randomUUID(), user = UUID.randomUUID(), owned = UUID.randomUUID(), membership = UUID.randomUUID();
            try (var insert = c.prepareStatement("INSERT INTO incidents(id,title,description,version) VALUES (?,'Legacy','Preserved',7)")) {
                insert.setObject(1,legacy); insert.executeUpdate();
            }
            version("4").migrate();
            try (var insert = c.prepareStatement("INSERT INTO organizations(id,name) VALUES (?,'Existing')")) { insert.setObject(1,org); insert.executeUpdate(); }
            try (var insert = c.prepareStatement("INSERT INTO app_users(id,issuer,subject) VALUES (?,'https://issuer.example','existing')")) { insert.setObject(1,user); insert.executeUpdate(); }
            try (var insert = c.prepareStatement("INSERT INTO organization_memberships(id,organization_id,user_id,role) VALUES (?,?,?,'ADMIN')")) {
                insert.setObject(1,membership); insert.setObject(2,org); insert.setObject(3,user); insert.executeUpdate();
            }
            try (var insert = c.prepareStatement("INSERT INTO incidents(id,organization_id,title,description,version) VALUES (?,?,'Owned','Preserved',9)")) {
                insert.setObject(1,owned); insert.setObject(2,org); insert.executeUpdate();
            }
            var before = snapshot(c);
            var latest = version("5"); assertEquals(1,latest.migrate().migrationsExecuted);
            assertTrue(latest.validateWithResult().validationSuccessful);
            assertEquals(before,snapshot(c)); assertEquals(0,latest.migrate().migrationsExecuted);
            try (var s = c.createStatement();var row = s.executeQuery("SELECT count(*) FROM documents")) { row.next(); assertEquals(0,row.getLong(1)); }
        }
    }
    String snapshot(java.sql.Connection c) throws Exception {
        var result = new StringBuilder();
        for (String table : java.util.List.of("incidents","app_users","organizations","organization_memberships")) {
            try (var s = c.createStatement();var rows = s.executeQuery("SELECT row_to_json(t)::text FROM "+table+" t ORDER BY id")) {
                while (rows.next()) result.append(rows.getString(1)).append('\n');
            }
        }
        return result.toString();
    }
}
