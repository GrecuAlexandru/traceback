package dev.traceback.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** Bound REST allocations before JSON deserialization, including chunked requests. */
final class ApiBodyLimitFilter extends OncePerRequestFilter {
    static final int MAX_BYTES = 131072;

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        // This filter belongs to the REST chain. Do not select by raw URI: encoded
        // path characters can be normalized later by the servlet container.
        return !Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod())
                || (type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/"));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getContentLengthLong() > MAX_BYTES) { reject(response); return; }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) { reject(response); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous REST request required"); }
                };
            }
            @Override public BufferedReader getReader() {
                String encoding = getCharacterEncoding();
                return new BufferedReader(new InputStreamReader(getInputStream(),
                        encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
            }
        }, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Payload Too Large\",\"status\":413,\"detail\":\"REST request body exceeds 128 KiB.\"}");
    }
}
