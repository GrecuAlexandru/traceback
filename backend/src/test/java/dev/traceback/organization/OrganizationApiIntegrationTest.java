package dev.traceback.organization;

import dev.traceback.organization.dto.CreateOrganizationRequest;
import dev.traceback.user.AppUser;
import dev.traceback.user.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OrganizationApiIntegrationTest {
    private static final String ISSUER = "http://localhost:8180/realms/traceback";

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("traceback.documents.storage-directory", dev.traceback.knowledge.TestDocumentStorage::directory);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // These tests exercise service authorization with simulated validated principals.
    // No live issuer is required, and they do not claim to verify signature/issuer/audience.
    @MockitoBean
    JwtDecoder decoder;

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired OrganizationMembershipRepository memberships;
    @Autowired OrganizationService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM incidents");
        memberships.deleteAll();
        organizations.deleteAll();
        users.deleteAll();
    }

    private RequestPostProcessor human(String subject) {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject)
                .claim("traceback_principal_type", "human")
                .claim("email", "shared@example.test"));
    }

    private Jwt humanToken(String subject) {
        return Jwt.withTokenValue("test-only").header("alg", "RS256")
                .issuer(ISSUER).subject(subject).claim("traceback_principal_type", "human").build();
    }

    private void create(String subject, String name) throws Exception {
        mvc.perform(post("/api/v1/organizations").with(human(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void createsOrganizationAndAdministratorAtomically() throws Exception {
        mvc.perform(post("/api/v1/organizations").with(human("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  Platform  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Platform"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        assertEquals(1, users.count());
        assertEquals(1, organizations.count());
        assertEquals(1, memberships.count());
        AppUser user = users.findAll().getFirst();
        assertEquals(ISSUER, user.getIssuer());
        assertEquals("alice", user.getSubject());
        assertNotNull(user.getCreatedAt());
        assertEquals(user.getId(), jdbc.queryForObject(
                "SELECT user_id FROM organization_memberships", UUID.class));
        assertEquals(organizations.findAll().getFirst().getId(), jdbc.queryForObject(
                "SELECT organization_id FROM organization_memberships", UUID.class));
    }

    @Test
    void listsOnlyOwnMembershipsAndReusesIdentity() throws Exception {
        create("alice", "First");
        create("alice", "Second");
        create("bob", "Private");
        mvc.perform(get("/api/v1/organizations").with(human("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Second"))
                .andExpect(jsonPath("$[1].name").value("First"));
        mvc.perform(get("/api/v1/organizations").with(human("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Private"));
        assertEquals(2, users.count()); // Same email does not combine identities.
    }

    @Test
    void emptyListingDoesNotProvisionUser() throws Exception {
        mvc.perform(get("/api/v1/organizations").with(human("new-user")))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        assertEquals(0, users.count());
    }

    @Test
    void issuerAndSubjectBothScopeMemberships() throws Exception {
        create("alice", "Local");
        jdbc.update("INSERT INTO app_users (id, issuer, subject) VALUES (?, ?, ?)",
                UUID.randomUUID(), "https://other-issuer.example.test", "alice");
        AppUser other = users.findByIssuerAndSubject("https://other-issuer.example.test", "alice").orElseThrow();
        Organization privateOrg = organizations.save(new Organization("Other issuer"));
        memberships.saveAndFlush(new OrganizationMembership(privateOrg, other, MembershipRole.ADMIN));
        mvc.perform(get("/api/v1/organizations").with(human("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Local"));
    }

    @Test
    void returnsDatabaseRolesForAllMembershipTypes() throws Exception {
        create("alice", "Admin org");
        AppUser user = users.findAll().getFirst();
        memberships.saveAndFlush(new OrganizationMembership(
                organizations.save(new Organization("Engineering")), user, MembershipRole.ENGINEER));
        memberships.saveAndFlush(new OrganizationMembership(
                organizations.save(new Organization("Read only")), user, MembershipRole.VIEWER));
        mvc.perform(get("/api/v1/organizations").with(human("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].role").value("VIEWER"))
                .andExpect(jsonPath("$[1].role").value("ENGINEER"))
                .andExpect(jsonPath("$[2].role").value("ADMIN"));
    }

    @Test
    void ignoresClientIdentityRoleAndForeignOrganizationSelectors() throws Exception {
        create("bob", "Private");
        UUID foreignId = organizations.findAll().getFirst().getId();
        mvc.perform(post("/api/v1/organizations").with(human("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Alice org", "userId":"%s", "subject":"bob",
                                 "issuer":"https://fake.example.test", "organizationId":"%s", "role":"VIEWER"}
                                """.formatted(users.findAll().getFirst().getId(), foreignId)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(get("/api/v1/organizations").with(human("alice"))
                        .param("organizationId", foreignId.toString()).param("subject", "bob"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Alice org"));
        assertEquals(2, users.count());
        assertEquals("alice", users.findByIssuerAndSubject(ISSUER, "alice").orElseThrow().getSubject());
    }

    @Test
    void rejectsUnauthenticatedAccess() throws Exception {
        mvc.perform(get("/api/v1/organizations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/organizations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Denied\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(0, users.count());
        assertEquals(0, organizations.count());
    }

    @Test
    void deniesGenericAndServiceAccountTokensDespitePrivilegedClaims() throws Exception {
        for (String type : List.of("absent", "service_account", "Human")) {
            RequestPostProcessor machine = jwt().jwt(token -> {
                token.issuer(ISSUER).subject("machine-client")
                        .claim("preferred_username", "service-account-client")
                        .claim("email", "machine@example.test")
                        .claim("roles", List.of("ADMIN"));
                if (!type.equals("absent")) token.claim("traceback_principal_type", type);
            });
            mvc.perform(post("/api/v1/organizations").with(machine)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Denied\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
            mvc.perform(get("/api/v1/organizations").with(machine))
                    .andExpect(status().isForbidden());
        }
        assertEquals(0, users.count());
        assertEquals(0, organizations.count());
        assertEquals(0, memberships.count());
    }

    @Test
    void humanPolicyRejectsMissingOrInvalidIdentityAndClaimType() throws Exception {
        List<RequestPostProcessor> invalid = List.of(
                jwt().jwt(t -> t.subject("alice").claim("traceback_principal_type", "human")),
                jwt().jwt(t -> t.issuer(ISSUER).subject(" ").claim("traceback_principal_type", "human")),
                jwt().jwt(t -> t.issuer(ISSUER).claims(c -> c.remove("sub"))
                        .claim("traceback_principal_type", "human")),
                jwt().jwt(t -> t.issuer(ISSUER).subject("x".repeat(256)).claim("traceback_principal_type", "human")),
                jwt().jwt(t -> t.issuer("https://example.test/" + "x".repeat(256)).subject("alice")
                        .claim("traceback_principal_type", "human")),
                jwt().jwt(t -> t.issuer(ISSUER).subject("alice").claim("traceback_principal_type", true))
        );
        for (RequestPostProcessor principal : invalid) {
            mvc.perform(post("/api/v1/organizations").with(principal)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Denied\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/organizations").with(principal))
                    .andExpect(status().isForbidden());
        }
        assertEquals(0, users.count());
    }

    @Test
    void rejectsInvalidNamesAndMalformedJsonWithoutWrites() throws Exception {
        for (String body : List.of("{}", "{\"name\":null}", "{\"name\":\"\"}",
                "{\"name\":\"   \"}", "{\"name\":\"" + "x".repeat(121) + "\"}", "{", "null")) {
            mvc.perform(post("/api/v1/organizations").with(human("alice"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
        assertEquals(0, users.count());
        assertEquals(0, organizations.count());
        assertEquals(0, memberships.count());
    }

    @Test
    void acceptsMaximumNameLength() throws Exception {
        create("alice", "x".repeat(120));
        assertEquals(120, organizations.findAll().getFirst().getName().length());
    }

    @Test
    void membershipFailureRollsBackOrganizationAndNewIdentity() throws Exception {
        // Install a fault only in the disposable PostgreSQL container, after the org INSERT.
        jdbc.execute("""
                CREATE FUNCTION reject_test_membership() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Simulated membership failure'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_test_membership BEFORE INSERT ON organization_memberships
                FOR EACH ROW EXECUTE FUNCTION reject_test_membership()
                """);
        try {
            assertThrows(RuntimeException.class, () -> service.create(humanToken("alice"),
                    new CreateOrganizationRequest("Rollback")));
            assertEquals(0, users.count());
            assertEquals(0, organizations.count());
            assertEquals(0, memberships.count());
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_membership ON organization_memberships");
            jdbc.execute("DROP FUNCTION reject_test_membership()");
        }
        create("alice", "Retry succeeds");
        assertEquals(1, memberships.count());
    }

    @Test
    void concurrentFirstUseCreatesOneUserAndAnAdminForEachOrganization() throws Exception {
        int workers = 6;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    create("first-use", "Concurrent " + index);
                    return null;
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertEquals(1, users.count());
        assertEquals(workers, organizations.count());
        assertEquals(workers, memberships.count());
        assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT user_id) FROM organization_memberships", Integer.class));
        assertEquals(workers, jdbc.queryForObject(
                "SELECT count(*) FROM organization_memberships WHERE role = 'ADMIN'", Integer.class));
    }

    @Test
    void schemaRejectsDuplicateIdentitiesMembershipsInvalidRolesAndForeignKeys() throws Exception {
        create("alice", "Constraints");
        UUID userId = users.findAll().getFirst().getId();
        UUID orgId = organizations.findAll().getFirst().getId();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO app_users (id, issuer, subject) VALUES (?, ?, ?)", UUID.randomUUID(), ISSUER, "alice"));
        assertThrows(DataIntegrityViolationException.class, () -> insertMembership(orgId, userId, "VIEWER"));
        assertThrows(DataIntegrityViolationException.class, () -> insertMembership(orgId, UUID.randomUUID(), "ADMIN"));
        assertThrows(DataIntegrityViolationException.class, () -> insertMembership(UUID.randomUUID(), userId, "ADMIN"));
        Organization second = organizations.save(new Organization("Second"));
        assertThrows(DataIntegrityViolationException.class, () -> insertMembership(second.getId(), userId, "OWNER"));
        assertEquals(1, memberships.count());
    }

    private void insertMembership(UUID orgId, UUID userId, String role) {
        jdbc.update("INSERT INTO organization_memberships (id, organization_id, user_id, role) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), orgId, userId, role);
    }

    @Test
    void organizationOperationsPreserveExistingIncidents() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID ownerId = organizations.save(new Organization("Existing owner")).getId();
        jdbc.update("""
                INSERT INTO incidents (id, organization_id, title, description)
                VALUES (?, ?, 'Existing', 'Preserved')
                """, incidentId, ownerId);
        var before = jdbc.queryForMap("SELECT * FROM incidents WHERE id = ?", incidentId);
        create("alice", "Platform");
        service.listMine(humanToken("alice"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM incidents WHERE id = ?", incidentId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM incidents", Integer.class));
    }
}
