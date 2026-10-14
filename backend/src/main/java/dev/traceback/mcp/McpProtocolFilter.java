package dev.traceback.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Narrow HTTP/envelope guard around the SDK, not an alternative MCP dispatcher. */
final class McpProtocolFilter extends OncePerRequestFilter {
    static final Set<String> VERSIONS=Set.of("2025-03-26","2025-06-18","2025-11-25");
    private final McpSettings settings;
    private final MeterRegistry metrics;
    private final Semaphore active;
    private final AtomicInteger count=new AtomicInteger();
    private long minute=System.nanoTime();
    private int requests;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    McpProtocolFilter(McpSettings settings,MeterRegistry metrics) {
        this.settings=settings;this.metrics=metrics;
        active=new Semaphore(settings.concurrentRequests());
        metrics.gauge("traceback.mcp.http.active",count);
    }
    private synchronized boolean admitted() {
        long now=System.nanoTime();if(now-minute>=60_000_000_000L){minute=now;requests=0;}
        if(requests>=settings.requestsPerMinute())return false;
        requests++;return true;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        response.setHeader("Cache-Control","no-store");
        if (!admitted() || !active.tryAcquire()) { response.setHeader("Retry-After","60");deny(response,429,"capacity");return; }
        count.incrementAndGet();
        try {
            if (!request.getRequestURI().equals(settings.endpoint())) {response.setStatus(404);return;}
            if (!request.getMethod().equals("POST")) {response.setHeader("Allow","POST");response.setStatus(405);return;}
            if (request.getHeader("Content-Encoding")!=null) {response.setStatus(415);return;}
            if (request.getContentType()==null || !request.getContentType().split(";",2)[0].strip().equalsIgnoreCase("application/json")) {response.setStatus(415);return;}
            try {
                var charset=org.springframework.http.MediaType.parseMediaType(request.getContentType()).getCharset();
                if(charset!=null && !charset.equals(StandardCharsets.UTF_8)) {response.setStatus(415);return;}
            } catch(IllegalArgumentException ex) {response.setStatus(415);return;}
            if (request.getContentLengthLong()>settings.maxRequestBytes()) {response.setStatus(413);return;}
            byte[] bytes=request.getInputStream().readNBytes(settings.maxRequestBytes()+1);
            if(bytes.length>settings.maxRequestBytes()) {response.setStatus(413);return;}
            JsonNode tree;
            try { tree=json.readTree(bytes); }
            catch (RuntimeException ex) {rpc(response,null,-32700,"Parse error");return;}
            Object id=null;
            if(tree!=null && tree.isObject() && tree.has("id") && (tree.get("id").isString() || tree.get("id").isIntegralNumber()))
                id=json.convertValue(tree.get("id"),Object.class);
            if(tree==null || !tree.isObject() || !"2.0".equals(tree.path("jsonrpc").asString())
                    || !tree.path("method").isString() || tree.path("method").asString().length()>64
                    || (tree.has("id") && (id==null || String.valueOf(id).length()>64))
                    || (tree.has("params") && !tree.get("params").isObject())) {
                rpc(response,id,-32600,"Invalid request");return;
            }
            String method=tree.get("method").asString();
            String version=request.getHeader("MCP-Protocol-Version");
            if(version!=null && !VERSIONS.contains(version)) {response.setStatus(400);return;}
            if(!tree.has("id")) {response.setStatus(202);return;} // No server->client requests, state, or cancellation authority.
            if(!Set.of("initialize","ping","tools/list","tools/call").contains(method)) {rpc(response,id,-32601,"Method not found");return;}
            var params=tree.path("params");
            if(method.equals("initialize") && (!params.path("protocolVersion").isString()
                    || !params.path("protocolVersion").asString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    || !params.path("capabilities").isObject() || !params.path("clientInfo").isObject()
                    || !text(params.path("clientInfo").path("name"),128) || !text(params.path("clientInfo").path("version"),128))) {
                rpc(response,id,-32602,"Invalid parameters");return;
            }
            if(method.equals("tools/call") && (!text(params.path("name"),64)
                    || (params.has("arguments") && !params.get("arguments").isObject()))) {
                rpc(response,id,-32602,"Invalid parameters");return;
            }
            if (!(request.getUserPrincipal() instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken caller)) {
                response.setStatus(403);return;
            }
            // Server-only proof that this request passed the dedicated MCP chain.
            // Encoded/alternate routes reaching a REST chain cannot create this attribute.
            request.setAttribute(McpConfiguration.CALLER,caller.getToken());
            chain.doFilter(new BodyRequest(request,bytes),response);
        } finally {count.decrementAndGet();active.release();}
    }
    private boolean text(JsonNode node,int max) {return node.isString() && !node.asString().isBlank() && node.asString().length()<=max;}
    private void deny(HttpServletResponse response,int status,String reason) {
        metrics.counter("traceback.mcp.http.denials","reason",reason).increment();response.setStatus(status);
    }
    private void rpc(HttpServletResponse response,Object id,int code,String message)throws IOException {
        var envelope=new LinkedHashMap<String,Object>();envelope.put("jsonrpc","2.0");envelope.put("id",id);
        envelope.put("error",Map.of("code",code,"message",message));
        response.setStatus(200);response.setContentType("application/json");response.getWriter().write(json.writeValueAsString(envelope));
    }
    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;
        BodyRequest(HttpServletRequest request,byte[] bytes){super(request);this.bytes=bytes;}
        @Override public ServletInputStream getInputStream() {
            var in=new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                public int read(){return in.read();}
                public boolean isFinished(){return in.available()==0;}
                public boolean isReady(){return true;}
                public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
            };
        }
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
    }
}
