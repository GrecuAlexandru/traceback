package dev.traceback.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import dev.traceback.knowledge.TestDocumentStorage;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Boot decoder + Spring filters, controlled local OIDC/JWK issuer; no mocked principal. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"traceback.ingestion.scheduling-enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RestJwtValidationIntegrationTest {
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    static final RSAKey TRUSTED=key(),FOREIGN=key();
    static final HttpServer OIDC=issuer();
    static final String ISSUER="http://127.0.0.1:"+OIDC.getAddress().getPort()+"/issuer";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->postgres.getJdbcUrl()+(postgres.getJdbcUrl().contains("?")?"&":"?")+"socketTimeout=2&connectTimeout=2");
        r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",()->ISSUER);
        r.add("traceback.documents.storage-directory",TestDocumentStorage::directory);
    }
    @LocalServerPort int port;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static RSAKey key() {try{return new RSAKeyGenerator(2048).keyID("controlled-test-key").generate();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static HttpServer issuer() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                String base="http://127.0.0.1:"+server.getAddress().getPort();
                String body=exchange.getRequestURI().getPath().equals("/jwks")?new JWKSet(TRUSTED.toPublicJWK()).toString()
                        :"{\"issuer\":\""+base+"/issuer\",\"jwks_uri\":\""+base+"/jwks\"}";
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();return server;
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @AfterAll static void stopIssuer(){OIDC.stop(0);}
    String token(RSAKey key,String issuer,String audience,Instant expiry,Instant notBefore)throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).subject("synthetic").audience(audience).issueTime(Date.from(Instant.now().minusSeconds(120)));
        if(expiry!=null)claims.expirationTime(Date.from(expiry));if(notBefore!=null)claims.notBeforeTime(Date.from(notBefore));
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims.build());jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
    int request(String path,String token)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(token!=null)request.header("Authorization","Bearer "+token);
        return client.send(request.GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    @Test void actualRestFiltersValidateSignatureIssuerAudienceExpiryAndNotBefore()throws Exception {
        Instant future=Instant.now().plusSeconds(300);
        assertEquals(200,request("/api/v1/me",token(TRUSTED,ISSUER,"traceback-api",future,null)));
        for(String bad:Arrays.asList(null,"malformed",
                token(FOREIGN,ISSUER,"traceback-api",future,null),token(TRUSTED,ISSUER+"/wrong","traceback-api",future,null),
                token(TRUSTED,ISSUER,"http://127.0.0.1:8080/mcp",future,null),
                token(TRUSTED,ISSUER,"traceback-api",Instant.now().minusSeconds(120),null),
                token(TRUSTED,ISSUER,"traceback-api",future,Instant.now().plusSeconds(120)),
                token(TRUSTED,ISSUER,"traceback-api",null,null)))assertEquals(401,request("/api/v1/me",bad));
        // Authentication succeeds, but no trusted-human classification grants bootstrap authority.
        assertEquals(403,request("/api/v1/organizations",token(TRUSTED,ISSUER,"traceback-api",future,null)));
    }
    @Test void postgresOutageMakesReadinessFailWithoutFailingLivenessAndRecovers()throws Exception {
        assertEquals(200,request("/actuator/health/readiness",null));
        var docker=postgres.getDockerClient();String id=postgres.getContainerId();
        docker.pauseContainerCmd(id).exec();
        try {
            assertEquals(200,request("/actuator/health/liveness",null));
            assertEquals(503,request("/actuator/health/readiness",null));
        } finally {docker.unpauseContainerCmd(id).exec();}
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();int status;
        do {status=request("/actuator/health/readiness",null);if(status==200)break;Thread.sleep(100);}while(System.nanoTime()<deadline);
        assertEquals(200,status);assertEquals(200,request("/actuator/health/liveness",null));
    }
}
