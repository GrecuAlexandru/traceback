package dev.traceback.deployment;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Health probe uses the runtime JRE; no curl, shell interpolation or credentials. */
public final class ContainerHealth {
    private ContainerHealth() {}
    public static void main(String[] args) {
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            int status = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            System.exit(status == 200 ? 0 : 1);
        } catch (Exception e) { System.exit(1); }
    }
}
