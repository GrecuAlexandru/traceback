package dev.traceback.ingestion;

import dev.traceback.knowledge.storage.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Set;

@Configuration
@EnableConfigurationProperties(IngestionSettings.class)
public class AwsConfiguration {
    // M11 is local-only. Cloud endpoint/credential-chain enablement requires a later deployment review.
    private URI localEndpoint(String value) {
        URI endpoint = URI.create(value);
        if (!"http".equals(endpoint.getScheme()) || !Set.of("localhost", "127.0.0.1", "localstack").contains(endpoint.getHost())
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("M11 requires a local AWS emulator endpoint");
        }
        return endpoint;
    }
    private ClientOverrideConfiguration timeouts() {
        return ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(20))
                .apiCallAttemptTimeout(Duration.ofSeconds(8)).build();
    }
    @Bean @ConditionalOnProperty(name = "traceback.aws.enabled", havingValue = "true")
    S3Client s3Client(@Value("${traceback.aws.endpoint}") String endpoint) {
        return S3Client.builder().endpointOverride(localEndpoint(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .forcePathStyle(true).overrideConfiguration(timeouts())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5))).build();
    }
    @Bean @ConditionalOnProperty(name = "traceback.aws.enabled", havingValue = "true")
    SqsClient sqsClient(@Value("${traceback.aws.endpoint}") String endpoint) {
        return SqsClient.builder().endpointOverride(localEndpoint(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .overrideConfiguration(timeouts())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(8))).build();
    }
    @Bean LocalDocumentStorage localDocumentStorage(@Value("${traceback.documents.storage-directory}") String directory,
                                                    @Value("${traceback.runtime:api}") String runtime) throws IOException {
        if (!Set.of("api", "worker").contains(runtime)) throw new IllegalArgumentException("Invalid runtime role");
        return new LocalDocumentStorage(directory, runtime.equals("worker"));
    }
    @Bean @Primary DocumentStorage documentStorage(LocalDocumentStorage local,
            org.springframework.beans.factory.ObjectProvider<S3Client> s3,
            @Value("${traceback.aws.bucket}") String bucket,
            @Value("${traceback.documents.provider:LOCAL}") String provider,
            @Value("${traceback.documents.max-file-size-bytes}") int maximum,
            @Value("${traceback.runtime:api}") String runtime) {
        var providers = new HashMap<String, DocumentStorage>();
        providers.put("LOCAL", local);
        if (s3.getIfAvailable() != null) providers.put("S3", new S3DocumentStorage(s3.getObject(), bucket, maximum, runtime.equals("worker")));
        return new RoutingDocumentStorage(providers, provider);
    }
}
