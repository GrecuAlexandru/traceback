package dev.traceback.rag;

import dev.traceback.organization.OrganizationPermissionDeniedException;
import dev.traceback.knowledge.TestDocumentStorage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties="traceback.ingestion.scheduling-enabled=false")
class ConversationIntegrationTest {
    static final String ROOT=TestDocumentStorage.directory();
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("traceback.documents.storage-directory",()->ROOT);}
    @MockitoBean JwtDecoder decoder;
    @Autowired ConversationService service;@Autowired ConversationRepository repository;@Autowired JdbcTemplate jdbc;
    UUID org,other;
    Jwt token(String name) {return Jwt.withTokenValue("synthetic").header("alg","RS256").issuer("https://issuer.example").subject(name).claim("traceback_principal_type","human").build();}
    @BeforeEach void setup() {
        for(String table:List.of("conversation_evidence","conversation_messages","conversation_turns","conversations","organization_memberships","organizations","app_users"))jdbc.update("DELETE FROM "+table);
        org=organization("A");other=organization("B");member(org,"alice","ADMIN");member(org,"viewer","VIEWER");member(other,"bob","ADMIN");
    }
    UUID organization(String name) {UUID id=UUID.randomUUID();jdbc.update("INSERT INTO organizations(id,name) VALUES (?,?)",id,name);return id;}
    void member(UUID org,String name,String role) {UUID id=UUID.randomUUID();jdbc.update("INSERT INTO app_users(id,issuer,subject) VALUES (?,'https://issuer.example',?)",id,name);jdbc.update("INSERT INTO organization_memberships(id,organization_id,user_id,role) VALUES (?,?,?,?)",UUID.randomUUID(),org,id,role);}
    @Test void createListRenameDeleteAndViewerOwnership() {
        var c=service.create(token("viewer"),org," Database recovery ");assertEquals("Database recovery",c.title());
        assertEquals(c.id(),service.list(token("viewer"),org,0,10).items().getFirst().id());assertTrue(service.list(token("alice"),org,0,10).items().isEmpty());
        assertEquals("New title",service.rename(token("viewer"),org,c.id(),"New title").title());service.delete(token("viewer"),org,c.id());
        assertEquals(0,service.list(token("viewer"),org,0,10).totalElements());assertThrows(ChatFailure.class,()->service.find(token("viewer"),org,c.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM conversations WHERE deleted_at IS NOT NULL",Integer.class));
    }
    @Test void foreignAndUnknownIdsHaveIdenticalErrorsAndRevocationUsesCurrentDatabase() {
        var c=service.create(token("alice"),org,"Private");var foreign=service.create(token("bob"),other,"Foreign");
        assertEquals("CONVERSATION_NOT_FOUND",assertThrows(ChatFailure.class,()->service.find(token("viewer"),org,c.id())).code());
        assertEquals("CONVERSATION_NOT_FOUND",assertThrows(ChatFailure.class,()->service.find(token("alice"),org,foreign.id())).code());
        assertThrows(OrganizationPermissionDeniedException.class,()->service.find(token("alice"),other,foreign.id()));
        jdbc.update("DELETE FROM organization_memberships WHERE user_id=(SELECT id FROM app_users WHERE subject='alice')");
        assertThrows(OrganizationPermissionDeniedException.class,()->service.list(token("alice"),org,0,10));
    }
    @Test void validatesBoundsAndRepositoryAlsoScopesReads() {
        assertThrows(IllegalArgumentException.class,()->service.create(token("alice"),org," "));
        assertThrows(IllegalArgumentException.class,()->service.list(token("alice"),org,0,101));
        assertThrows(IllegalArgumentException.class,()->service.list(token("alice"),org,-1,1));
        var c=service.create(token("alice"),org,"Private");
        var who=new dev.traceback.security.HumanIdentityPolicy.Identity("https://issuer.example","viewer");
        assertThrows(ChatFailure.class,()->repository.find(org,c.id(),who,false));assertThrows(ChatFailure.class,()->repository.rename(org,c.id(),who,"Hacked"));
    }
    @Test void insertFailureRollsBackConversation() {
        jdbc.execute("CREATE FUNCTION reject_chat() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic'; END $$");
        jdbc.execute("CREATE TRIGGER reject_chat BEFORE INSERT ON conversations FOR EACH ROW EXECUTE FUNCTION reject_chat()");
        try {assertThrows(org.springframework.dao.DataAccessException.class,()->service.create(token("alice"),org,"Fail"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM conversations",Integer.class));}
        finally {jdbc.execute("DROP TRIGGER reject_chat ON conversations");jdbc.execute("DROP FUNCTION reject_chat()");}
    }
}
