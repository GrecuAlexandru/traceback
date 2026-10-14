package dev.traceback.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;

@Component
@ConditionalOnProperty(name="traceback.aws.enabled",havingValue="true")
public class SqsIngestionQueue implements IngestionQueue {
    private final SqsClient client;
    private final String name;
    private final IngestionSettings settings;
    public SqsIngestionQueue(SqsClient client,@Value("${traceback.aws.queue-name}") String name,IngestionSettings settings) {
        this.client=client;this.name=name;this.settings=settings;
    }
    // Resolve lazily: outages must not prevent durable API upload/transactional event creation.
    private String url() { return client.getQueueUrl(GetQueueUrlRequest.builder().queueName(name).build()).queueUrl(); }
    @Override public void send(String body) {
        client.sendMessage(SendMessageRequest.builder().queueUrl(url()).messageBody(body).build());
    }
    @Override public List<Delivery> receive() {
        return client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(url()).maxNumberOfMessages(1)
                .waitTimeSeconds(5).visibilityTimeout(settings.visibilitySeconds()).build()).messages().stream()
                .map(m->new Delivery(m.body(),m.receiptHandle())).toList();
    }
    @Override public void acknowledge(String receipt) {
        client.deleteMessage(DeleteMessageRequest.builder().queueUrl(url()).receiptHandle(receipt).build());
    }
    @Override public void defer(String receipt,int seconds) {
        client.changeMessageVisibility(ChangeMessageVisibilityRequest.builder().queueUrl(url()).receiptHandle(receipt)
                .visibilityTimeout(seconds).build());
    }
    @Override public boolean ready() {
        try { client.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url())
                .attributeNames(QueueAttributeName.QUEUE_ARN).build());return true; }
        catch (RuntimeException ex) { return false; }
    }
}
