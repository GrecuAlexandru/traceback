package dev.traceback.ingestion;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name={"traceback.aws.enabled","traceback.ingestion.scheduling-enabled"},havingValue="true")
public class IngestionRuntime {
    private final OutboxDispatcher dispatcher;
    private final QueueConsumer consumer;
    private final IngestionRepository repository;
    private final MeterRegistry metrics;
    private final boolean worker;
    public IngestionRuntime(OutboxDispatcher dispatcher,QueueConsumer consumer,IngestionRepository repository,MeterRegistry metrics,
                            @Value("${traceback.runtime:api}") String role) {
        this.dispatcher=dispatcher;this.consumer=consumer;this.repository=repository;this.metrics=metrics;worker=role.equals("worker");
    }
    @Scheduled(fixedDelayString="${traceback.ingestion.poll-delay-ms:1000}")
    public void dispatch() { run("dispatch",()-> { for (int i=0;i<20 && dispatcher.dispatchOne();i++) { /* bounded batch */ } }); }
    @Scheduled(fixedDelayString="${traceback.ingestion.poll-delay-ms:1000}")
    public void consume() { if (worker) run("consume",consumer::pollOnce); }
    @Scheduled(fixedDelayString="${traceback.ingestion.recovery-delay-ms:30000}")
    public void recover() { run("recovery",()->repository.reconcile()); }
    private void run(String operation,Runnable action) {
        try { action.run(); }
        catch (RuntimeException ex) {
            metrics.counter("traceback.ingestion.runtime.failures","operation",operation).increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Ingestion {} deferred after dependency failure",operation);
        }
    }
}
