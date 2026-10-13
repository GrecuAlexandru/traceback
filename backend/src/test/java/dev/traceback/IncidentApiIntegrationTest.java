package dev.traceback;

import dev.traceback.incident.IncidentRepository;
import dev.traceback.incident.IncidentService;
import dev.traceback.incident.IncidentStatus;
import dev.traceback.incident.IncidentNotFoundException;
import dev.traceback.incident.dto.CreateIncidentRequest;
import dev.traceback.incident.dto.UpdateIncidentRequest;
import dev.traceback.organization.MembershipRole;
import dev.traceback.organization.OrganizationPermissionDeniedException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.Map;
import com.jayway.jsonpath.JsonPath;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class IncidentApiIntegrationTest {
    private static final String ISSUER = "http://localhost:8180/realms/traceback";
    private static final UUID LEGACY_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) throws Exception {
        // Insert real pre-ownership data at V3; Boot then upgrades the disposable DB to V4.
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target("3").load().migrate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var insert = connection.prepareStatement("""
                     INSERT INTO incidents (id, title, description, status, version, created_at)
                     VALUES (?, 'Legacy private history', 'Unassigned', 'RESOLVED', 7, '2026-01-01T00:00:00Z')
                     """)) {
            insert.setObject(1, LEGACY_ID);
            insert.executeUpdate();
        }
        registry.add("traceback.documents.storage-directory", dev.traceback.knowledge.TestDocumentStorage::directory);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Simulated validated principals test application authorization, not live Keycloak.
    @MockitoBean JwtDecoder decoder;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IncidentService service;
    @Autowired IncidentRepository repository;
    @Autowired PlatformTransactionManager transactions;

    private UUID orgA;
    private UUID orgB;
    private UUID aliceId;

    @BeforeEach
    void setUp() {
        // Preserve the pre-V4 quarantine fixture through every test.
        jdbc.update("DELETE FROM incidents WHERE organization_id IS NOT NULL");
        jdbc.update("DELETE FROM organization_memberships");
        jdbc.update("DELETE FROM organizations");
        jdbc.update("DELETE FROM app_users");
        orgA = organization("A");
        orgB = organization("B");
        aliceId = member("alice", orgA, MembershipRole.ADMIN);
        member("engineer", orgA, MembershipRole.ENGINEER);
        member("viewer", orgA, MembershipRole.VIEWER);
        member("bob", orgB, MembershipRole.ADMIN);
    }

    private UUID organization(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID member(String subject, UUID org, MembershipRole role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, issuer, subject) VALUES (?, ?, ?) ON CONFLICT (issuer, subject) DO NOTHING",
                id, ISSUER, subject);
        UUID userId = jdbc.queryForObject("SELECT id FROM app_users WHERE issuer = ? AND subject = ?", UUID.class, ISSUER, subject);
        jdbc.update("INSERT INTO organization_memberships (id, organization_id, user_id, role) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), org, userId, role.name());
        return userId;
    }

    private RequestPostProcessor human(String subject) {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("traceback_principal_type", "human"));
    }

    private Jwt token(String subject) {
        return Jwt.withTokenValue("test-only").header("alg", "RS256").issuer(ISSUER).subject(subject)
                .claim("traceback_principal_type", "human").build();
    }

    private String collection(UUID org) { return "/api/v1/organizations/" + org + "/incidents"; }
    private String item(UUID org, UUID id) { return collection(org) + "/" + id; }

    private UUID incident(UUID org, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO incidents (id, organization_id, title, description) VALUES (?, ?, ?, 'Description')", id, org, title);
        return id;
    }

    private long ownedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM incidents WHERE organization_id IS NOT NULL", Long.class);
    }

    @Test
    void shouldCreateAndRetrieveIncident() throws Exception {
        mvc.perform(post(collection(orgA)).with(human("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Database failure\",\"description\":\"Connections are exhausted\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(collection(orgA) + "/")))
                .andExpect(jsonPath("$.organizationId").value(orgA.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(0));
        UUID id = jdbc.queryForObject("SELECT id FROM incidents WHERE organization_id = ?", UUID.class, orgA);
        mvc.perform(get(item(orgA, id)).with(human("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Database failure"))
                .andExpect(jsonPath("$.description").value("Connections are exhausted"));
        assertEquals(1, ownedCount());
    }

    @Test
    void shouldUpdateIncidentAndPersistChanges() throws Exception {
        UUID id = incident(orgA, "Original title");
        mvc.perform(patch(item(orgA, id)).with(human("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated title\",\"status\":\"INVESTIGATING\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated title"))
                .andExpect(jsonPath("$.status").value("INVESTIGATING"))
                .andExpect(jsonPath("$.version").value(1));
        var persisted = jdbc.queryForMap("SELECT * FROM incidents WHERE id = ?", id);
        assertEquals("Description", persisted.get("description"));
        assertEquals(orgA, persisted.get("organization_id"));
        assertEquals(1L, persisted.get("version"));
    }

    @Test
    void shouldDeleteIncidentWithMatchingVersion() throws Exception {
        UUID id = incident(orgA, "Temporary");
        mvc.perform(delete(item(orgA, id)).param("version", "0").with(human("alice")))
                .andExpect(status().isNoContent());
        assertEquals(0, ownedCount());
        mvc.perform(get(item(orgA, id)).with(human("alice"))).andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectInvalidIncident() throws Exception {
        for (String body : List.of("{\"title\":\"\",\"description\":\"\"}", "{}", "{",
                "{\"title\":\"" + "x".repeat(201) + "\",\"description\":\"Too long\"}")) {
            mvc.perform(post(collection(orgA)).with(human("alice")).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        assertEquals(0, ownedCount());
    }

    @Test
    void shouldReturnIdenticalNotFoundForMissingForeignAndLegacyIncidents() throws Exception {
        UUID foreignId = incident(orgB, "Secret B");
        Map<String, Object> reference = null;
        for (UUID id : List.of(UUID.randomUUID(), foreignId, LEGACY_ID)) {
            for (MockHttpServletRequestBuilder request : List.of(get(item(orgA, id)),
                    patch(item(orgA, id)).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\",\"version\":7}"),
                    delete(item(orgA, id)).param("version", "7"))) {
                String body = mvc.perform(request.with(human("alice")))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.detail").value("Incident not found."))
                        .andReturn().getResponse().getContentAsString();
                // The instance URI naturally differs; no private state/version appears.
                assertFalse(body.contains("Secret B"));
                assertFalse(body.contains("Legacy private history"));
                Map<String, Object> problem = JsonPath.parse(body).read("$");
                problem.remove("instance");
                if (reference == null) reference = problem;
                else assertEquals(reference, problem);
            }
        }
        assertEquals(1, ownedCount());
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM incidents WHERE id = ?", Long.class, foreignId));
        assertEquals(7L, jdbc.queryForObject("SELECT version FROM incidents WHERE id = ?", Long.class, LEGACY_ID));
    }

    @Test
    void shouldPaginateOnlyAuthorizedOrganizationWithCorrectCounts() throws Exception {
        for (int i = 0; i < 7; i++) incident(orgA, "A " + i);
        for (int i = 0; i < 5; i++) incident(orgB, "Secret B " + i);
        mvc.perform(get(collection(orgA)).with(human("alice")).param("page", "1").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.items[*].organizationId").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(orgA.toString()))));
        mvc.perform(get(collection(orgB)).with(human("bob")).param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void shouldFilterByStatusWithoutCountingOtherOrganizationsOrLegacy() throws Exception {
        incident(orgA, "Open A");
        UUID investigating = incident(orgA, "Investigating A");
        jdbc.update("UPDATE incidents SET status = 'INVESTIGATING' WHERE id = ?", investigating);
        UUID foreignId = incident(orgB, "Secret investigating B");
        jdbc.update("UPDATE incidents SET status = 'INVESTIGATING' WHERE id = ?", foreignId);
        mvc.perform(get(collection(orgA)).with(human("alice")).param("status", "INVESTIGATING").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(investigating.toString()));
        mvc.perform(get(collection(orgA)).with(human("alice")).param("status", "RESOLVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void shouldRejectStaleUpdatesAndDeletesWithoutMutation() throws Exception {
        UUID id = incident(orgA, "Original");
        mvc.perform(patch(item(orgA, id)).with(human("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(patch(item(orgA, id)).with(human("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Outdated\",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        mvc.perform(delete(item(orgA, id)).with(human("alice")).param("version", "0"))
                .andExpect(status().isConflict());
        assertEquals("Original", jdbc.queryForObject("SELECT title FROM incidents WHERE id = ?", String.class, id));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM incidents WHERE id = ?", Long.class, id));
    }

    @Test
    void noOpPatchPreservesVersionAndStaleNoOpStillConflicts() throws Exception {
        UUID id = incident(orgA, "Original");
        for (String body : List.of("{\"version\":0}", "{\"title\":\"Original\",\"description\":\"Description\",\"status\":\"OPEN\",\"version\":0}")) {
            mvc.perform(patch(item(orgA, id)).with(human("alice")).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        }
        service.updateIncident(token("alice"), orgA, id, new UpdateIncidentRequest("Changed", null, null, 0L));
        mvc.perform(patch(item(orgA, id)).with(human("alice")).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldRejectInvalidPatchAndDeleteVersions() throws Exception {
        UUID id = incident(orgA, "Example");
        for (String body : List.of("{\"status\":\"RESOLVED\"}", "{\"version\":-1}",
                "{\"version\":0,\"title\":\" \"}", "{\"version\":0,\"description\":\" \"}", "{\"version\":0,\"status\":\"INVALID\"}")) {
            mvc.perform(patch(item(orgA, id)).with(human("alice")).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(delete(item(orgA, id)).with(human("alice"))).andExpect(status().isBadRequest());
        mvc.perform(delete(item(orgA, id)).with(human("alice")).param("version", "-1")).andExpect(status().isBadRequest());
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM incidents WHERE id = ?", Long.class, id));
    }

    @Test
    void shouldRejectUnauthenticatedRequests() throws Exception {
        UUID id = incident(orgA, "Protected");
        for (MockHttpServletRequestBuilder request : allOperations(orgA, id)) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        assertEquals(1, ownedCount());
    }

    private List<MockHttpServletRequestBuilder> allOperations(UUID org, UUID id) {
        return List.of(get(collection(org)), get(item(org, id)),
                post(collection(org)).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"New\",\"description\":\"Text\"}"),
                patch(item(org, id)).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Changed\",\"version\":0}"),
                delete(item(org, id)).param("version", "0"));
    }

    @Test
    void shouldReturnAuthenticatedIdentity() throws Exception {
        mvc.perform(get("/api/v1/me").with(jwt().jwt(t -> t.issuer(ISSUER).subject("alice")
                        .claim("traceback_principal_type", "human").claim("preferred_username", "demo-engineer"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subject").value("alice"))
                .andExpect(jsonPath("$.username").value("demo-engineer"))
                .andExpect(jsonPath("$.issuer").value(ISSUER));
    }

    @Test
    void nonMembersAndUnknownOrganizationsReturn403ForEveryOperation() throws Exception {
        UUID foreignId = incident(orgB, "Secret B");
        for (UUID org : List.of(orgB, UUID.randomUUID())) {
            for (MockHttpServletRequestBuilder request : allOperations(org, foreignId)) {
                mvc.perform(request.with(human("alice")))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403))
                        .andExpect(jsonPath("$.detail").value("You do not have permission to access this organization's incidents."));
            }
        }
        assertEquals(1, ownedCount());
        assertEquals("Secret B", jdbc.queryForObject("SELECT title FROM incidents WHERE id = ?", String.class, foreignId));
    }

    @Test
    void viewerCanReadButEveryWriteReturns403RegardlessOfIncidentExistence() throws Exception {
        UUID id = incident(orgA, "Readable");
        mvc.perform(get(collection(orgA)).with(human("viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(item(orgA, id)).with(human("viewer"))).andExpect(status().isOk());
        for (UUID target : List.of(id, incident(orgB, "Secret"), LEGACY_ID, UUID.randomUUID())) {
            var operations = allOperations(orgA, target);
            for (MockHttpServletRequestBuilder write : operations.subList(2, operations.size())) {
                mvc.perform(write.with(human("viewer"))).andExpect(status().isForbidden());
            }
        }
        assertEquals(2, ownedCount());
        assertEquals("Readable", jdbc.queryForObject("SELECT title FROM incidents WHERE id = ?", String.class, id));
    }

    @Test
    void engineerCanCreateReadUpdateAndDelete() throws Exception {
        var created = service.createIncident(token("engineer"), orgA, new CreateIncidentRequest("Engineer", "Owned"));
        mvc.perform(get(item(orgA, created.id())).with(human("engineer"))).andExpect(status().isOk());
        mvc.perform(patch(item(orgA, created.id())).with(human("engineer")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INVESTIGATING\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(delete(item(orgA, created.id())).with(human("engineer")).param("version", "1"))
                .andExpect(status().isNoContent());
        assertEquals(0, ownedCount());
    }

    @Test
    void rolesAreSpecificToOrganizationAndNotTrustedFromJwt() throws Exception {
        member("alice", orgB, MembershipRole.VIEWER);
        UUID foreignId = incident(orgB, "B read only");
        mvc.perform(get(item(orgB, foreignId)).with(human("alice"))).andExpect(status().isOk());
        mvc.perform(patch(item(orgB, foreignId)).with(jwt().jwt(t -> t.issuer(ISSUER).subject("alice")
                        .claim("traceback_principal_type", "human").claim("roles", List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Denied\",\"version\":0}"))
                .andExpect(status().isForbidden());
        assertEquals("B read only", jdbc.queryForObject("SELECT title FROM incidents WHERE id = ?", String.class, foreignId));
    }

    @Test
    void clientOwnershipAndIdentityFieldsCannotChangeScope() throws Exception {
        mvc.perform(post(collection(orgA)).with(human("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Owned A", "description":"Text", "organizationId":"%s",
                                 "userId":"%s", "subject":"bob", "role":"ADMIN"}
                                """.formatted(orgB, aliceId)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.organizationId").value(orgA.toString()));
        UUID id = jdbc.queryForObject("SELECT id FROM incidents WHERE organization_id = ?", UUID.class, orgA);
        mvc.perform(patch(item(orgA, id)).with(human("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + orgB + "\",\"title\":\"Still A\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.organizationId").value(orgA.toString()));
        mvc.perform(get(collection(orgB)).with(human("bob")).param("organizationId", orgA.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void serviceEnforcesAuthorizationWithoutController() {
        UUID id = incident(orgA, "A");
        assertThrows(OrganizationPermissionDeniedException.class, () -> service.getIncident(token("bob"), orgA, id));
        assertThrows(OrganizationPermissionDeniedException.class, () -> service.getIncidents(token("bob"), orgA, null, 0, 20));
        assertThrows(OrganizationPermissionDeniedException.class, () -> service.createIncident(token("viewer"), orgA, new CreateIncidentRequest("No", "No")));
        assertThrows(OrganizationPermissionDeniedException.class, () -> service.updateIncident(token("viewer"), orgA, id, new UpdateIncidentRequest("No", null, null, 0L)));
        assertThrows(OrganizationPermissionDeniedException.class, () -> service.deleteIncident(token("viewer"), orgA, id, 0));
        assertThrows(IncidentNotFoundException.class, () -> service.getIncident(token("alice"), orgA, incident(orgB, "Private")));
        assertEquals("A", jdbc.queryForObject("SELECT title FROM incidents WHERE id = ?", String.class, id));
    }

    @Test
    void repositoryPredicatesIndependentlyEnforceIdentityRoleAndTenant() {
        UUID id = incident(orgA, "Original");
        UUID foreign = incident(orgB, "Private");
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(ignored -> {
            assertTrue(repository.findAuthorizedById(orgA, id, ISSUER, "bob").isEmpty());
            assertTrue(repository.findAuthorizedById(orgA, foreign, ISSUER, "alice").isEmpty());
            assertTrue(repository.findAuthorizedById(orgA, LEGACY_ID, ISSUER, "alice").isEmpty());
            assertEquals(0, repository.findAuthorized(orgA, ISSUER, "bob", org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements());
            for (String subject : List.of("bob", "viewer")) {
                assertEquals(0, repository.insertAuthorized(orgA, UUID.randomUUID(), "Denied", "Denied", ISSUER, subject));
                assertEquals(0, repository.updateAuthorized(orgA, id, 0, "Denied", "Denied", "RESOLVED", ISSUER, subject));
                assertEquals(0, repository.deleteAuthorized(orgA, id, 0, ISSUER, subject));
            }
            assertEquals(0, repository.updateAuthorized(orgA, foreign, 0, "Denied", "Denied", "RESOLVED", ISSUER, "alice"));
            assertEquals(0, repository.deleteAuthorized(orgA, foreign, 0, ISSUER, "alice"));
        });
        assertEquals(2, ownedCount());
        assertEquals("Original", jdbc.queryForObject("SELECT title FROM incidents WHERE id = ?", String.class, id));
        assertEquals("Private", jdbc.queryForObject("SELECT title FROM incidents WHERE id = ?", String.class, foreign));
    }

    @Test
    void issuerIsPartOfIdentityForMembershipAuthorization() throws Exception {
        UUID id = incident(orgA, "Private");
        mvc.perform(get(item(orgA, id)).with(jwt().jwt(t -> t.issuer("https://other-issuer.example.test")
                        .subject("alice").claim("traceback_principal_type", "human"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void machinePrincipalIsDeniedEvenWithDatabaseMembership() throws Exception {
        for (MockHttpServletRequestBuilder request : allOperations(orgA, incident(orgA, "Protected"))) {
            mvc.perform(request.with(jwt().jwt(t -> t.issuer(ISSUER).subject("alice").claim("roles", List.of("ADMIN")))))
                    .andExpect(status().isForbidden());
        }
        assertEquals(1, ownedCount());
    }

    @Test
    void removedOrDowngradedMembershipTakesEffectWithoutChangingToken() throws Exception {
        UUID id = incident(orgA, "Original");
        jdbc.update("UPDATE organization_memberships SET role = 'VIEWER' WHERE organization_id = ? AND user_id = ?", orgA, aliceId);
        mvc.perform(patch(item(orgA, id)).with(human("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Denied\",\"version\":0}"))
                .andExpect(status().isForbidden());
        jdbc.update("DELETE FROM organization_memberships WHERE organization_id = ? AND user_id = ?", orgA, aliceId);
        mvc.perform(get(item(orgA, id)).with(human("alice"))).andExpect(status().isForbidden());
        assertEquals("Original", jdbc.queryForObject("SELECT title FROM incidents WHERE id = ?", String.class, id));
    }

    @Test
    void globalRoutesAreDeniedForEveryMethodAndKnownId() throws Exception {
        mvc.perform(get("/api/v1/incidents")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/incidents")).andExpect(status().isUnauthorized());
        UUID ownedId = incident(orgA, "Protected");
        for (String path : List.of("/api/v1/incidents", "/api/v1/incidents/" + ownedId, "/api/v1/incidents/" + LEGACY_ID)) {
            for (MockHttpServletRequestBuilder request : List.of(get(path), post(path), patch(path), delete(path))) {
                mvc.perform(request.with(human("alice")).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"Denied\",\"description\":\"Text\",\"version\":0}"))
                        .andExpect(status().isForbidden());
            }
        }
        assertEquals(1, ownedCount());
    }

    @Test
    void legacyIncidentRemainsIntactAndInvisibleToEveryOrganization() throws Exception {
        var before = jdbc.queryForMap("SELECT * FROM incidents WHERE id = ?", LEGACY_ID);
        assertNull(before.get("organization_id"));
        for (var pair : List.of(new Object[]{orgA, "alice"}, new Object[]{orgB, "bob"})) {
            UUID org = (UUID) pair[0];
            String subject = (String) pair[1];
            mvc.perform(get(collection(org)).with(human(subject)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
            for (MockHttpServletRequestBuilder request : List.of(get(item(org, LEGACY_ID)),
                    patch(item(org, LEGACY_ID)).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"No\",\"version\":7}"),
                    delete(item(org, LEGACY_ID)).param("version", "7"))) {
                mvc.perform(request.with(human(subject))).andExpect(status().isNotFound());
            }
        }
        assertEquals(before, jdbc.queryForMap("SELECT * FROM incidents WHERE id = ?", LEGACY_ID));
    }

    @Test
    void rejectsInvalidPaginationStatusAndIds() throws Exception {
        for (String[] pair : List.of(new String[]{"page", "-1"}, new String[]{"size", "0"}, new String[]{"size", "101"},
                new String[]{"status", "INVALID"})) {
            mvc.perform(get(collection(orgA)).with(human("alice")).param(pair[0], pair[1]))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(collection(orgA) + "/not-a-uuid").with(human("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/organizations/not-a-uuid/incidents").with(human("alice"))).andExpect(status().isBadRequest());
    }

    @Test
    void postMutationAuthorizationFailureRollsBackIncidentAndMembershipChanges() {
        UUID id = incident(orgA, "Original");
        var before = jdbc.queryForMap("SELECT * FROM incidents WHERE id = ?", id);
        // Simulate an authorization change after a successful SQL write but before
        // response assembly, exclusively inside this disposable test database.
        jdbc.execute("""
                CREATE FUNCTION revoke_test_membership() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    DELETE FROM organization_memberships WHERE organization_id = NEW.organization_id
                      AND user_id IN (SELECT id FROM app_users WHERE subject = 'alice');
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER revoke_test_membership AFTER INSERT OR UPDATE ON incidents
                FOR EACH ROW EXECUTE FUNCTION revoke_test_membership()
                """);
        try {
            assertThrows(IncidentNotFoundException.class, () -> service.updateIncident(token("alice"), orgA, id,
                    new UpdateIncidentRequest("Rollback", null, null, 0L)));
            assertEquals(before, jdbc.queryForMap("SELECT * FROM incidents WHERE id = ?", id));
            assertThrows(IncidentNotFoundException.class, () -> service.createIncident(token("alice"), orgA,
                    new CreateIncidentRequest("Rollback creation", "Text")));
            assertEquals(1, ownedCount());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM organization_memberships WHERE organization_id = ? AND user_id = ?",
                    Integer.class, orgA, aliceId));
        } finally {
            jdbc.execute("DROP TRIGGER revoke_test_membership ON incidents");
            jdbc.execute("DROP FUNCTION revoke_test_membership()");
        }
    }

    @Test
    void simultaneousUpdateAndDeleteWithSameVersionHaveExactlyOneWinner() throws Exception {
        UUID id = incident(orgA, "Original");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var update = executor.submit(() -> concurrentPatch(id, "Winner", ready, start));
            var delete = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return mvc.perform(delete(item(orgA, id)).with(human("alice")).param("version", "0"))
                        .andReturn().getResponse().getStatus();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int updateStatus = update.get(20, TimeUnit.SECONDS);
            int deleteStatus = delete.get(20, TimeUnit.SECONDS);
            if (updateStatus == 200) {
                assertEquals(409, deleteStatus);
                assertEquals(1, ownedCount());
                assertEquals(1L, jdbc.queryForObject("SELECT version FROM incidents WHERE id = ?", Long.class, id));
            } else {
                assertEquals(204, deleteStatus);
                // The update may observe the deletion before lookup (404), or lose
                // its compare-and-swap after an authorized lookup (409).
                assertTrue(updateStatus == 404 || updateStatus == 409);
                assertEquals(0, ownedCount());
            }
        }
    }

    @Test
    void simultaneousPatchesWithSameVersionHaveExactlyOneWinner() throws Exception {
        UUID id = incident(orgA, "Original");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentPatch(id, "First", ready, start));
            var second = executor.submit(() -> concurrentPatch(id, "Second", ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(Set.of(200, 409), Set.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
        }
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM incidents WHERE id = ?", Long.class, id));
        assertTrue(Set.of("First", "Second").contains(jdbc.queryForObject("SELECT title FROM incidents WHERE id = ?", String.class, id)));
    }

    private int concurrentPatch(UUID id, String title, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return mvc.perform(patch(item(orgA, id)).with(human("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"version\":0}"))
                .andReturn().getResponse().getStatus();
    }
}
