package dev.traceback.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.util.List;

@ConfigurationProperties("traceback.mcp")
public record McpSettings(@DefaultValue("false") boolean enabled,
        @DefaultValue("/mcp") String endpoint,
        @DefaultValue("http://127.0.0.1:8080/mcp") String resource,
        @DefaultValue("http://127.0.0.1:8080/mcp") String audience,
        @DefaultValue({"localhost", "127.0.0.1", "[::1]"}) List<String> allowedHosts,
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue("16384") int maxRequestBytes,
        @DefaultValue("65536") int maxResultBytes,
        @DefaultValue("8") int concurrentRequests,
        @DefaultValue("4") int concurrentTools,
        @DefaultValue("120") int requestsPerMinute,
        @DefaultValue("10") int toolTimeoutSeconds) {
    public McpSettings {
        if (!endpoint.matches("/[a-zA-Z0-9_-]+") || endpoint.equals("/error")
                || endpoint.equals("/api") || endpoint.equals("/actuator"))
            throw new IllegalArgumentException("MCP endpoint must be a dedicated top-level path");
        URI uri = URI.create(resource);
        if (!"http".equals(uri.getScheme()) || !List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                || !endpoint.equals(uri.getPath()) || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getUserInfo()!=null
                || !resource.equals(audience)) throw new IllegalArgumentException("MCP resource and audience must be the same loopback endpoint URI");
        allowedHosts = List.copyOf(allowedHosts); allowedOrigins = List.copyOf(allowedOrigins);
        if (allowedHosts.isEmpty() || !List.of("localhost", "127.0.0.1", "[::1]").containsAll(allowedHosts))
            throw new IllegalArgumentException("M14 MCP hosts must be loopback hosts");
        for (String origin:allowedOrigins) {
            URI o=URI.create(origin);
            if (!"http".equals(o.getScheme()) || !allowedHosts.contains(o.getHost()) || o.getUserInfo()!=null
                    || o.getRawQuery()!=null || o.getRawFragment()!=null || !o.getPath().isEmpty())
                throw new IllegalArgumentException("M14 MCP origins must be exact loopback origins");
        }
        if (maxRequestBytes<1024 || maxRequestBytes>65536 || maxResultBytes<4096 || maxResultBytes>131072
                || concurrentRequests<1 || concurrentRequests>32 || concurrentTools<1 || concurrentTools>concurrentRequests
                || requestsPerMinute<1 || requestsPerMinute>600 || toolTimeoutSeconds<1 || toolTimeoutSeconds>30)
            throw new IllegalArgumentException("Invalid MCP resource limits");
    }
    public String metadataPath() { return "/.well-known/oauth-protected-resource"+endpoint; }
    public String metadataUrl() { URI uri=URI.create(resource);return uri.getScheme()+"://"+uri.getRawAuthority()+metadataPath(); }
    public boolean matches(String path) { return path.equals(endpoint) || path.startsWith(endpoint+"/") || path.equals(metadataPath()); }
}
