package dev.traceback.ingestion;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestionObservability {
    public IngestionObservability(IngestionRepository repository,MeterRegistry metrics) {
        metrics.gauge("traceback.outbox.pending",repository,IngestionRepository::pendingEvents);
        metrics.gauge("traceback.outbox.failed",repository,IngestionRepository::publicationFailures);
        metrics.gauge("traceback.ingestion.expired.leases",repository,IngestionRepository::expiredLeases);
        metrics.gauge("traceback.ingestion.failed.jobs",repository,IngestionRepository::failedJobs);
    }
}
