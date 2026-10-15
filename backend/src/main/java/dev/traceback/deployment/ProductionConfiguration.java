package dev.traceback.deployment;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Explicit production profile fails closed; development settings stay local. */
@Configuration
@Profile("production")
public class ProductionConfiguration {
    // Validate before datasource/storage/scheduler bean creation, including recovery.
    @org.springframework.context.annotation.Bean
    static org.springframework.beans.factory.config.BeanFactoryPostProcessor productionGuard(Environment environment) {
        validate(environment);
        return factory -> { };
    }

    static void validate(Environment e) {
        requireDatabaseTls(required(e, "spring.datasource.url"));
        required(e, "spring.datasource.username");
        String password = required(e, "spring.datasource.password");
        if (Set.of("traceback_dev", "test", "password", "changeme").contains(password)) fail();
        URI issuer = URI.create(required(e, "spring.security.oauth2.resourceserver.jwt.issuer-uri"));
        if (!"https".equals(issuer.getScheme()) || issuer.getHost() == null || issuer.getUserInfo() != null
                || issuer.getQuery() != null || issuer.getFragment() != null
                || Set.of("localhost", "127.0.0.1", "[::1]").contains(issuer.getHost())) fail();
        if (!"traceback-api".equals(required(e, "spring.security.oauth2.resourceserver.jwt.audiences[0]"))
                || e.getProperty("spring.security.oauth2.resourceserver.jwt.audiences[1]") != null
                || e.getProperty("traceback.mcp.enabled", Boolean.class, false)
                || e.getProperty("spring.flyway.enabled", Boolean.class, true)
                || !e.getProperty("traceback.deployment.validate-schema", Boolean.class, false)
                || !"validate".equals(e.getProperty("spring.jpa.hibernate.ddl-auto"))
                || !"none".equals(e.getProperty("server.forward-headers-strategy"))
                || !"health".equals(e.getProperty("management.endpoints.web.exposure.include"))
                || !"never".equals(e.getProperty("management.endpoint.health.show-details"))
                || !"aws".equals(e.getProperty("traceback.aws.mode"))
                || !e.getProperty("traceback.aws.enabled", Boolean.class, false)
                || !"S3".equals(e.getProperty("traceback.documents.provider"))
                || !"1".equals(required(e, "traceback.deployment.api-replicas"))) fail();
        required(e, "traceback.aws.region");
        required(e, "traceback.aws.bucket");
        required(e, "traceback.aws.queue-name");
        if (!required(e, "traceback.aws.s3-kms-key").matches("arn:aws:kms:[a-z0-9-]+:[0-9]{12}:key/[a-zA-Z0-9-]+")) fail();
        if (e.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class, 0) < 3) fail();
        if (!Set.of("api", "worker").contains(required(e, "traceback.runtime"))) fail();
        // Neither the existing Docker sandbox nor local model adapters are Fargate-ready.
        if (!"docker-host".equals(required(e, "traceback.deployment.extraction-runtime"))) fail();
        if (!required(e, "traceback.extraction.image").matches("[a-zA-Z0-9./:_-]+@sha256:[0-9a-f]{64}")) fail();
    }
    public static void requireDatabaseTls(String url) {
        if (!url.startsWith("jdbc:postgresql://") || !url.contains("?")) fail();
        var options = Arrays.stream(url.substring(url.indexOf('?') + 1).split("&")).toList();
        if (options.stream().filter(v -> v.startsWith("sslmode=")).count() != 1
                || !options.contains("sslmode=verify-full")
                || options.stream().anyMatch(v -> v.startsWith("sslfactory=") || v.startsWith("sslhostnameverifier="))
                || options.stream().noneMatch(v -> v.startsWith("sslrootcert=/"))) fail();
    }
    private static String required(Environment e, String key) {
        String value = e.getProperty(key);
        if (value == null || value.isBlank()) fail();
        return value;
    }
    private static void fail() { throw new IllegalArgumentException("Unsafe or incomplete production configuration"); }
}
