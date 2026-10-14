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
    private final dev.traceback.indexing.IndexWorker indexing;
    public QueueConsumer(IngestionQueue queue,IngestionWorker worker,MeterRegistry metrics,dev.traceback.indexing.IndexWorker indexing) {
        this.queue=queue;this.worker=worker;this.metrics=metrics;this.indexing=indexing;
    }
    public void pollOnce() {
        for (var delivery:queue.receive()) {
            // Exceptions (including DB/ack outages) escape; receipt remains unacknowledged for redelivery.
            var handling=delivery.body()!=null && delivery.body().startsWith("v2:") ? indexing.handle(delivery.body(),delivery.receipt()) : worker.handle(delivery.body());
            if (handling.acknowledge()) queue.acknowledge(delivery.receipt());
            else queue.defer(delivery.receipt(),handling.delaySeconds());
            metrics.counter("traceback.ingestion.deliveries").increment();
        }
    }
}
