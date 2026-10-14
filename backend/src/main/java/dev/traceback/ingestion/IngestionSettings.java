package dev.traceback.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("traceback.ingestion")
public record IngestionSettings(int leaseSeconds, int visibilitySeconds, int maxAttempts,
                                int baseBackoffSeconds, int maxBackoffSeconds, int recoverySeconds) {
    public IngestionSettings {
        if (leaseSeconds < 60 || leaseSeconds > 600 || visibilitySeconds < leaseSeconds || visibilitySeconds > 900
                || maxAttempts < 1 || maxAttempts > 20 || baseBackoffSeconds < 1 || maxBackoffSeconds < baseBackoffSeconds
                || maxBackoffSeconds > 900 || recoverySeconds < visibilitySeconds) {
            throw new IllegalArgumentException("Invalid ingestion bounds");
        }
    }
    public int backoff(int attempts) {
        long bound = Math.min(maxBackoffSeconds, (long) baseBackoffSeconds << Math.min(attempts - 1, 20));
        // Equal jitter, always at least one second; cap prevents unbounded queue invisibility.
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(Math.max(1, (int) bound / 2), (int) bound + 1);
    }
}
