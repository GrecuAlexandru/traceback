package dev.traceback.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.util.*;

/** Runs before Spring's automatically registered protected-resource metadata filter. */
final class McpBoundaryFilter extends OncePerRequestFilter {
    private final McpSettings settings;
    private final MeterRegistry metrics;
    private final boolean api;
    private final String issuer;
    McpBoundaryFilter(McpSettings settings,MeterRegistry metrics,boolean api,String issuer) {
        this.settings=settings;this.metrics=metrics;this.api=api;this.issuer=issuer;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        response.setHeader("Cache-Control","no-store");
        if (!settings.enabled() || !api) {response.setStatus(404);return;}
        if (!validHost(request) || !validOrigin(request) || request.getHeader("Forwarded")!=null
                || Collections.list(request.getHeaderNames()).stream().anyMatch(h -> h.toLowerCase(Locale.ROOT).startsWith("x-forwarded-"))) {
            deny(response,403,"boundary");return;
        }
        if (request.getRequestURI().equals(settings.metadataPath())) {
            if (!request.getMethod().equals("GET")) {response.setHeader("Allow","GET");response.setStatus(405);return;}
            response.setStatus(200);response.setContentType("application/json");
            response.getWriter().write(JsonMapper.builder().build().writeValueAsString(Map.of("resource",settings.resource(),
                    "authorization_servers",List.of(issuer),"bearer_methods_supported",List.of("header"))));
            return;
        }
        chain.doFilter(request,response);
    }
    private boolean validOrigin(HttpServletRequest request) {
        var origins=Collections.list(request.getHeaders("Origin"));
        return origins.isEmpty() || (origins.size()==1 && settings.allowedOrigins().contains(origins.getFirst()));
    }
    private boolean validHost(HttpServletRequest request) {
        var hosts=Collections.list(request.getHeaders("Host"));
        if(hosts.size()!=1)return false;
        try {
            String host=hosts.getFirst();URI uri=URI.create("http://"+host);
            return uri.getRawAuthority().equals(host) && uri.getUserInfo()==null && uri.getPath().isEmpty()
                    && uri.getRawQuery()==null && uri.getRawFragment()==null && settings.allowedHosts().contains(uri.getHost())
                    && (uri.getPort()<0 ? 80 : uri.getPort())==request.getLocalPort();
        } catch(RuntimeException ex) {return false;}
    }
    private void deny(HttpServletResponse response,int status,String reason) {
        metrics.counter("traceback.mcp.http.denials","reason",reason).increment();response.setStatus(status);
    }
}
