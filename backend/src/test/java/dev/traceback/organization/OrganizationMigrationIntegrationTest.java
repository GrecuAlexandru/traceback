package dev.traceback.organization;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class OrganizationMigrationIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void upgradingV2ToV3PreservesExistingIncidentAndVersion() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target("2").load().migrate();
        UUID incidentId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO incidents (id, title, description, status, version, created_at)
                    VALUES (?, 'Legacy', 'Keep this history', 'RESOLVED', 7, '2026-01-01T00:00:00Z')
                    """)) {
                insert.setObject(1, incidentId);
                insert.executeUpdate();
            }
            Flyway flyway = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("3").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertTrue(flyway.validateWithResult().validationSuccessful);
            try (var query = connection.prepareStatement("SELECT * FROM incidents WHERE id = ?")) {
                query.setObject(1, incidentId);
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("Legacy", row.getString("title"));
                    assertEquals("Keep this history", row.getString("description"));
                    assertEquals("RESOLVED", row.getString("status"));
                    assertEquals(7, row.getLong("version"));
                    assertEquals("2026-01-01T00:00:00Z", row.getTimestamp("created_at").toInstant().toString());
                    assertFalse(row.next());
                }
            }
            try (var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT count(*) FROM organization_memberships")) {
                assertTrue(row.next());
                assertEquals(0, row.getInt(1));
            }
        }
    }
}
