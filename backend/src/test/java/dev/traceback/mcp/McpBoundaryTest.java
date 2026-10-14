package dev.traceback.mcp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class McpBoundaryTest {
    McpSettings settings(boolean enabled,int rate,int concurrent) {
        return new McpSettings(enabled,"/mcp","http://127.0.0.1:8080/mcp","http://127.0.0.1:8080/mcp",List.of("127.0.0.1"),List.of(),16384,65536,concurrent,1,rate,1);
    }
    MockHttpServletRequest request(String path) {
        var r=new MockHttpServletRequest("POST",path);r.addHeader("Host","127.0.0.1:8080");r.setLocalPort(8080);
        r.setUserPrincipal(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("synthetic").header("alg","RS256").issuer("https://issuer.invalid").subject("alice").build()));r.setContentType("application/json");
        r.setContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));return r;
    }
    @Test void disabledEndpointAndMetadataAreAbsentEvenForAuthenticatedCallers()throws Exception {
        var filter=new McpBoundaryFilter(settings(false,10,1),new SimpleMeterRegistry(),true,"https://issuer.invalid");
        for(String path:List.of("/mcp","/mcp/", "/.well-known/oauth-protected-resource/mcp")) {
            var response=new MockHttpServletResponse();filter.doFilter(request(path),response,(q,s)->fail("Disabled endpoint was dispatched"));assertEquals(404,response.getStatus());
        }
    }
    @Test void workerRuntimeCannotExposeMcp()throws Exception {
        var response=new MockHttpServletResponse();new McpBoundaryFilter(settings(true,10,1),new SimpleMeterRegistry(),false,"https://issuer.invalid")
                .doFilter(request("/mcp"),response,(q,s)->fail());assertEquals(404,response.getStatus());
    }
    @Test void sharedRateLimitIsBoundedWithoutPerUserCacheAndReturnsRetryAfter()throws Exception {
        var filter=new McpProtocolFilter(settings(true,1,1),new SimpleMeterRegistry());
        filter.doFilter(request("/mcp"),new MockHttpServletResponse(),(q,s)->{});
        var rejected=new MockHttpServletResponse();filter.doFilter(request("/mcp"),rejected,(q,s)->fail());
        assertEquals(429,rejected.getStatus());assertEquals("60",rejected.getHeader("Retry-After"));
    }
    @Test void activeHttpLimitRejectsImmediatelyAndReleasesAfterCompletion()throws Exception {
        var registry=new SimpleMeterRegistry();var filter=new McpProtocolFilter(settings(true,10,1),registry);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(()->{try{filter.doFilter(request("/mcp"),new MockHttpServletResponse(),(q,s)->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});}catch(Exception ex){throw new RuntimeException(ex);}});
            try{assertTrue(entered.await(2,TimeUnit.SECONDS));var rejected=new MockHttpServletResponse();filter.doFilter(request("/mcp"),rejected,(q,s)->fail());assertEquals(429,rejected.getStatus());}
            finally{release.countDown();}first.get();
        }
        assertEquals(0,registry.find("traceback.mcp.http.active").gauge().value());
        var accepted=new MockHttpServletResponse();filter.doFilter(request("/mcp"),accepted,(q,s)->{});assertEquals(200,accepted.getStatus());
    }
    @Test void unknownLengthBodiesCannotBypassSizeLimit()throws Exception {
        var r=request("/mcp");r.setContent("x".repeat(20000).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var wrapped=new jakarta.servlet.http.HttpServletRequestWrapper(r) {
            @Override public long getContentLengthLong(){return -1;}
            @Override public int getContentLength(){return -1;}
        };
        var response=new MockHttpServletResponse();new McpProtocolFilter(settings(true,10,1),new SimpleMeterRegistry()).doFilter(wrapped,response,(q,s)->fail());assertEquals(413,response.getStatus());
    }
    @Test void unsafeSettingsFailStartup() {
        assertThrows(IllegalArgumentException.class,()->new McpSettings(true,"/api","http://127.0.0.1:8080/api","http://127.0.0.1:8080/api",List.of("127.0.0.1"),List.of(),16384,65536,8,4,120,10));
        assertThrows(IllegalArgumentException.class,()->new McpSettings(true,"/mcp","http://127.0.0.1:8080/mcp","traceback-api",List.of("127.0.0.1"),List.of(),16384,65536,8,4,120,10));
        assertThrows(IllegalArgumentException.class,()->new McpSettings(true,"/mcp","http://example.invalid/mcp","http://example.invalid/mcp",List.of("example.invalid"),List.of(),16384,65536,8,4,120,10));
    }
}
