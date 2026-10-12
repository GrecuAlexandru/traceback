package dev.traceback;

import dev.traceback.incident.Incident;
import dev.traceback.incident.IncidentRepository;
import dev.traceback.incident.IncidentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class IncidentApiIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("traceback_test")
            .withUsername("traceback")
            .withPassword("test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IncidentRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }


    @Test
    void shouldCreateAndRetrieveIncident() throws Exception {

        String request = """
        {
            "title": "Database failure",
            "description": "Connections are exhausted"
        }
        """;

        mockMvc.perform(post("/api/v1/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Database failure"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        assertEquals(1, repository.count());

        Incident saved = repository.findAll().getFirst();

        assertNotNull(saved.getId());
        assertEquals("Database failure", saved.getTitle());

        mockMvc.perform(get("/api/v1/incidents/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Database failure"))
                .andExpect(jsonPath("$.description")
                        .value("Connections are exhausted"));
    }


    @Test
    void shouldUpdateIncidentAndPersistChanges() throws Exception {

        Incident incident = repository.save(
                new Incident("Original title", "Original description")
        );

        UUID id = incident.getId();

        String request = """
        {
            "title": "Updated title",
            "status": "INVESTIGATING"
        }
        """;

        mockMvc.perform(patch("/api/v1/incidents/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated title"))
                .andExpect(jsonPath("$.status").value("INVESTIGATING"));

        Incident updated = repository.findById(id).orElseThrow();

        assertEquals("Updated title", updated.getTitle());
        assertEquals(
                "Original description",
                updated.getDescription()
        );
        assertEquals(
                IncidentStatus.INVESTIGATING,
                updated.getStatus()
        );
    }


    @Test
    void shouldDeleteIncident() throws Exception {

        Incident incident = repository.save(
                new Incident("Temporary incident", "To be deleted")
        );

        UUID id = incident.getId();

        mockMvc.perform(delete("/api/v1/incidents/{id}", id))
                .andExpect(status().isNoContent());

        assertFalse(repository.existsById(id));

        mockMvc.perform(get("/api/v1/incidents/{id}", id))
                .andExpect(status().isNotFound());
    }


    @Test
    void shouldRejectInvalidIncident() throws Exception {

        String request = """
        {
            "title": "",
            "description": ""
        }
        """;

        mockMvc.perform(post("/api/v1/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());

        assertEquals(0, repository.count());
    }

    @Test
    void shouldReturnNotFoundForMissingIncident() throws Exception {

        UUID missingId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/incidents/{id}", missingId))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/incidents/{id}", missingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"status": "RESOLVED"}
                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/incidents/{id}", missingId))
                .andExpect(status().isNotFound());
    }

}
