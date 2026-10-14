package dev.traceback.mcp;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class McpJwtValidationTest {
    String signed(RSAKey key,String issuer,String audience,Instant expires)throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).subject("synthetic").audience(audience).issueTime(Date.from(Instant.now().minusSeconds(120)));
        if(expires!=null)claims.expirationTime(Date.from(expires));
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims.build());jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
    @Test void decoderIndependentlyValidatesIssuerAudienceExpiryAndSignature()throws Exception {
        RSAKey trusted=new RSAKeyGenerator(2048).keyID("trusted").generate(),foreign=new RSAKeyGenerator(2048).keyID("trusted").generate();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/protocol/openid-connect/certs",exchange->{byte[] bytes=new JWKSet(trusted.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try {
            String issuer="http://127.0.0.1:"+server.getAddress().getPort(),audience="http://127.0.0.1:8080/mcp";
            var decoder=McpSecurityConfiguration.decoder(issuer,audience);Instant future=Instant.now().plusSeconds(300);
            assertEquals("synthetic",decoder.decode(signed(trusted,issuer,audience,future)).getSubject());
            assertThrows(JwtException.class,()->decoder.decode(signed(trusted,issuer+"/wrong",audience,future)));
            assertThrows(JwtException.class,()->decoder.decode(signed(trusted,issuer,"traceback-api",future)));
            assertThrows(JwtException.class,()->decoder.decode(signed(trusted,issuer,audience,Instant.now().minusSeconds(1))));
            assertThrows(JwtException.class,()->decoder.decode(signed(trusted,issuer,audience,null)));
            assertThrows(JwtException.class,()->decoder.decode(signed(foreign,issuer,audience,future)));
        } finally {server.stop(0);}
    }
}
