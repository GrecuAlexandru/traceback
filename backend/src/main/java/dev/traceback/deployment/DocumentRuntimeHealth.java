package dev.traceback.deployment;

import dev.traceback.knowledge.DocumentUploadRecovery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("documentRuntime")
public class DocumentRuntimeHealth implements HealthIndicator {
    private final DocumentUploadRecovery recovery;
    private final boolean worker;
    public DocumentRuntimeHealth(DocumentUploadRecovery recovery, @Value("${traceback.runtime:api}") String runtime) {
        this.recovery = recovery; worker = runtime.equals("worker");
    }
    @Override public Health health() {
        try {
            if (!worker) recovery.requireActiveRuntime();
            return Health.up().build();
        } catch (RuntimeException e) { return Health.down().build(); }
    }
}
