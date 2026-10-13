package dev.traceback.incident;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class IncidentOwnershipMigrationIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void upgradePreservesLegacyButRejectsNewUnownedRowsAndUnsafeOrganizationDeletion() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target("3").load().migrate();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            UUID legacyId = UUID.randomUUID();
            try (var insert = connection.prepareStatement("""
                    INSERT INTO incidents (id, title, description, status, created_at, version)
                    VALUES (?, 'Legacy', 'Private history', 'INVESTIGATING', '2025-11-01T00:00:00Z', 12)
                    """)) {
                insert.setObject(1, legacyId);
                insert.executeUpdate();
            }
            Flyway flyway = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("4").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertTrue(flyway.validateWithResult().validationSuccessful);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            try (var query = connection.prepareStatement("SELECT * FROM incidents WHERE id = ?")) {
                query.setObject(1, legacyId);
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("Legacy", row.getString("title"));
                    assertEquals("Private history", row.getString("description"));
                    assertEquals("INVESTIGATING", row.getString("status"));
                    assertEquals(12, row.getLong("version"));
                    assertEquals("2025-11-01T00:00:00Z", row.getTimestamp("created_at").toInstant().toString());
                    assertNull(row.getObject("organization_id"));
                }
            }
            try (var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT convalidated FROM pg_constraint WHERE conname = 'incidents_organization_required'")) {
                assertTrue(row.next());
                assertFalse(row.getBoolean(1)); // Existing NULL rows have intentionally not been assigned.
            }
            assertSqlState("23514", () -> insertIncident(connection, UUID.randomUUID(), null));
            assertSqlState("23503", () -> insertIncident(connection, UUID.randomUUID(), UUID.randomUUID()));
            try (var update = connection.prepareStatement("UPDATE incidents SET title = 'No unowned mutation' WHERE id = ?")) {
                update.setObject(1, legacyId);
                assertSqlState("23514", update::executeUpdate);
            }
            UUID orgId = UUID.randomUUID();
            try (var insertOrg = connection.prepareStatement("INSERT INTO organizations (id, name) VALUES (?, 'Owner')")) {
                insertOrg.setObject(1, orgId);
                insertOrg.executeUpdate();
            }
            UUID ownedId = UUID.randomUUID();
            insertIncident(connection, ownedId, orgId);
            try (var query = connection.prepareStatement("SELECT organization_id FROM incidents WHERE id = ?")) {
                query.setObject(1, ownedId);
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(orgId, row.getObject(1, UUID.class));
                }
            }
            try (var update = connection.prepareStatement("UPDATE incidents SET organization_id = NULL WHERE id = ?")) {
                update.setObject(1, ownedId);
                assertSqlState("23514", update::executeUpdate);
            }
            try (var deleteOrg = connection.prepareStatement("DELETE FROM organizations WHERE id = ?")) {
                deleteOrg.setObject(1, orgId);
                assertSqlState("23503", deleteOrg::executeUpdate);
            }
            try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT count(*) FROM incidents")) {
                assertTrue(row.next());
                assertEquals(2, row.getInt(1));
            }
        }
    }

    private void insertIncident(Connection connection, UUID id, UUID organizationId) throws SQLException {
        try (var insert = connection.prepareStatement(
                "INSERT INTO incidents (id, organization_id, title, description) VALUES (?, ?, 'Owned', 'Description')")) {
            insert.setObject(1, id);
            insert.setObject(2, organizationId);
            insert.executeUpdate();
        }
    }

    private void assertSqlState(String state, SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        assertEquals(state, failure.getSQLState());
    }

    private interface SqlAction { void run() throws SQLException; }
}
