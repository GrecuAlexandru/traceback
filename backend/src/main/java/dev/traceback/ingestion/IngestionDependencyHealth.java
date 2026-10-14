package dev.traceback.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

@Component("ingestionDependencies")
@ConditionalOnProperty(name="traceback.aws.enabled",havingValue="true")
public class IngestionDependencyHealth implements HealthIndicator {
    private final IngestionQueue queue;
    private final S3Client s3;
    private final String bucket;
    public IngestionDependencyHealth(IngestionQueue queue,S3Client s3,@Value("${traceback.aws.bucket}") String bucket) {
        this.queue=queue;this.s3=s3;this.bucket=bucket;
    }
    @Override public Health health() {
        try {
            if (!queue.ready()) return Health.down().build();
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return Health.up().build();
        } catch (RuntimeException ex) { return Health.down().build(); }
    }
}
