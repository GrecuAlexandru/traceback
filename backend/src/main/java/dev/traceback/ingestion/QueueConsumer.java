package dev.traceback.ingestion;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="traceback.aws.enabled",havingValue="true")
public class QueueConsumer {
    private final IngestionQueue queue;
    private final IngestionWorker worker;
    private final MeterRegistry metrics;
    public QueueConsumer(IngestionQueue queue,IngestionWorker worker,MeterRegistry metrics) {
        this.queue=queue;this.worker=worker;this.metrics=metrics;
    }
    public void pollOnce() {
        for (var delivery:queue.receive()) {
            // Exceptions (including DB/ack outages) escape; receipt remains unacknowledged for redelivery.
            var handling=worker.handle(delivery.body());
            if (handling.acknowledge()) queue.acknowledge(delivery.receipt());
            else queue.defer(delivery.receipt(),handling.delaySeconds());
            metrics.counter("traceback.ingestion.deliveries").increment();
        }
    }
}
