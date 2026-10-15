package dev.traceback.deployment;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ProductionConfigurationTest {
    @Test void productionGuardRunsBeforeRegularBeans() {
        var created = new java.util.concurrent.atomic.AtomicBoolean();
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=production")
                .withUserConfiguration(ProductionConfiguration.class)
                .withBean("sideEffect", Object.class, () -> { created.set(true); return new Object(); })
                .run(context -> { assertNotNull(context.getStartupFailure()); assertFalse(created.get()); });
    }
    MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.internal/traceback?sslmode=verify-full&sslrootcert=/certs/rds.pem")
                .withProperty("spring.datasource.username", "app_runtime").withProperty("spring.datasource.password", "synthetic-only-random-value")
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://auth.example.test/realms/traceback")
                .withProperty("spring.security.oauth2.resourceserver.jwt.audiences[0]", "traceback-api")
                .withProperty("spring.flyway.enabled", "false").withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("traceback.deployment.validate-schema", "true")
                .withProperty("server.forward-headers-strategy", "none").withProperty("management.endpoints.web.exposure.include", "health")
                .withProperty("management.endpoint.health.show-details", "never").withProperty("traceback.aws.mode", "aws")
                .withProperty("traceback.aws.enabled", "true").withProperty("traceback.aws.region", "eu-example-1")
                .withProperty("traceback.aws.bucket", "synthetic-bucket").withProperty("traceback.aws.queue-name", "synthetic-queue")
                .withProperty("traceback.aws.s3-kms-key", "arn:aws:kms:eu-example-1:123456789012:key/synthetic-key")
                .withProperty("traceback.documents.provider", "S3").withProperty("traceback.deployment.api-replicas", "1")
                .withProperty("spring.datasource.hikari.maximum-pool-size", "8").withProperty("traceback.runtime", "api")
                .withProperty("traceback.deployment.extraction-runtime", "docker-host")
                .withProperty("traceback.extraction.image", "registry.example.test/traceback-pdf@sha256:" + "a".repeat(64));
    }
    @Test void acceptsOnlyExplicitSingletonProductionConfiguration() {
        assertDoesNotThrow(() -> ProductionConfiguration.validate(valid()));
        assertDoesNotThrow(() -> ProductionConfiguration.validate(valid().withProperty("traceback.runtime", "worker")));
    }
    @Test void rejectsUnsafeConfigurationWithoutEchoingSecrets() {
        Map<String, String> invalid = Map.ofEntries(
                Map.entry("spring.datasource.url", "jdbc:postgresql://db/traceback?sslmode=require"),
                Map.entry("spring.datasource.password", "traceback_dev"),
                Map.entry("spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://localhost:8180/realms/traceback"),
                Map.entry("spring.security.oauth2.resourceserver.jwt.audiences[0]", "https://mcp.example.test/mcp"),
                Map.entry("spring.security.oauth2.resourceserver.jwt.audiences[1]", "extra"),
                Map.entry("traceback.extraction.image", "traceback-pdf:m12"),
                Map.entry("traceback.mcp.enabled", "true"), Map.entry("spring.flyway.enabled", "true"),
                Map.entry("spring.jpa.hibernate.ddl-auto", "update"), Map.entry("server.forward-headers-strategy", "framework"),
                Map.entry("management.endpoints.web.exposure.include", "health,metrics"),
                Map.entry("traceback.aws.mode", "emulator"), Map.entry("traceback.documents.provider", "LOCAL"),
                Map.entry("traceback.deployment.api-replicas", "2"), Map.entry("traceback.deployment.extraction-runtime", "fargate"),
                Map.entry("spring.datasource.hikari.maximum-pool-size", "1"));
        invalid.forEach((key, value) -> assertEquals("Unsafe or incomplete production configuration",
                assertThrows(IllegalArgumentException.class, () -> ProductionConfiguration.validate(valid().withProperty(key, value))).getMessage()));
    }
    @Test void rejectsTlsBypassesAndMissingMigrationCredentials() {
        for (String url : new String[]{"jdbc:postgresql://db/t", "jdbc:postgresql://db/t?sslmode=verify-full",
                "jdbc:postgresql://db/t?sslmode=verify-full&sslmode=disable&sslrootcert=/cert",
                "jdbc:postgresql://db/t?sslmode=verify-full&sslrootcert=/cert&sslfactory=unsafe"})
            assertThrows(IllegalArgumentException.class, () -> ProductionConfiguration.requireDatabaseTls(url));
        assertThrows(IllegalArgumentException.class, () -> DatabaseMigration.run(Map.of()));
    }
}
