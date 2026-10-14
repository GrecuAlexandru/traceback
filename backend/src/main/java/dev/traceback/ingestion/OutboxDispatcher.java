package dev.traceback.ingestion;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="traceback.aws.enabled",havingValue="true")
public class OutboxDispatcher {
    private final IngestionRepository repository;
    private final IngestionQueue queue;
    private final IngestionSettings settings;
    private final MeterRegistry metrics;
    public OutboxDispatcher(IngestionRepository repository,IngestionQueue queue,IngestionSettings settings,MeterRegistry metrics) {
        this.repository=repository;this.queue=queue;this.settings=settings;this.metrics=metrics;
    }
    public boolean dispatchOne() {
        var event=repository.claimEvent();
        if (event==null) return false;
        try {
            queue.send(event.body());
        } catch (RuntimeException ex) {
            repository.publishFailed(event,settings.backoff(event.attempts()));
            metrics.counter("traceback.outbox.publish.failures").increment();
            return true;
        }
        // If this commit fails, leave the lease intact. Retry after expiry may duplicate the send.
        if (repository.delivered(event)) metrics.counter("traceback.outbox.published").increment();
        return true;
    }
}
