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
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import org.springframework.core.env.Environment;
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
    // Endpoint overrides and dummy credentials are restricted to explicit emulator mode.
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
    S3Client s3Client(Environment environment) {
        var builder = S3Client.builder().overrideConfiguration(timeouts())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)));
        if (cloud(environment)) {
            var region = region(environment);
            builder.region(region).endpointOverride(URI.create("https://s3." + region.id() + ".amazonaws.com"))
                    .credentialsProvider(DefaultCredentialsProvider.builder().build());
        } else {
            builder.endpointOverride(localEndpoint(environment.getRequiredProperty("traceback.aws.endpoint"))).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).forcePathStyle(true);
        }
        return builder.build();
    }
    @Bean @ConditionalOnProperty(name = "traceback.aws.enabled", havingValue = "true")
    SqsClient sqsClient(Environment environment) {
        var builder = SqsClient.builder().overrideConfiguration(timeouts())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(8)));
        if (cloud(environment)) {
            var region = region(environment);
            builder.region(region).endpointOverride(URI.create("https://sqs." + region.id() + ".amazonaws.com"))
                    .credentialsProvider(DefaultCredentialsProvider.builder().build());
        } else {
            builder.endpointOverride(localEndpoint(environment.getRequiredProperty("traceback.aws.endpoint"))).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        }
        return builder.build();
    }
    private boolean cloud(Environment environment) {
        String mode = environment.getProperty("traceback.aws.mode", "emulator");
        if (!Set.of("aws", "emulator").contains(mode)) throw new IllegalArgumentException("Invalid AWS mode");
        if (mode.equals("aws") && environment.getProperty("traceback.aws.endpoint") != null
                && !environment.getProperty("traceback.aws.endpoint").isBlank())
            throw new IllegalArgumentException("AWS mode forbids endpoint overrides");
        if (mode.equals("aws") && java.util.List.of("AWS_ENDPOINT_URL", "AWS_ENDPOINT_URL_S3", "AWS_ENDPOINT_URL_SQS",
                "aws.endpointUrl", "aws.endpointUrl.s3", "aws.endpointUrl.sqs").stream()
                .anyMatch(k -> environment.getProperty(k) != null))
            throw new IllegalArgumentException("AWS mode forbids external endpoint overrides");
        return mode.equals("aws");
    }
    private Region region(Environment environment) {
        String value = environment.getRequiredProperty("traceback.aws.region");
        if (!value.matches("[a-z]{2}-[a-z]+-[0-9]+") || value.startsWith("cn-"))
            throw new IllegalArgumentException("Explicit commercial AWS region required");
        return Region.of(value);
    }
    @Bean @ConditionalOnProperty(name="traceback.documents.legacy-enabled",havingValue="true",matchIfMissing=true)
    LocalDocumentStorage localDocumentStorage(@Value("${traceback.documents.storage-directory}") String directory,
                                                    @Value("${traceback.runtime:api}") String runtime) throws IOException {
        if (!Set.of("api", "worker").contains(runtime)) throw new IllegalArgumentException("Invalid runtime role");
        return new LocalDocumentStorage(directory, runtime.equals("worker"));
    }
    @Bean @Primary DocumentStorage documentStorage(org.springframework.beans.factory.ObjectProvider<LocalDocumentStorage> local,
            org.springframework.beans.factory.ObjectProvider<S3Client> s3,
            @Value("${traceback.aws.bucket}") String bucket,
            @Value("${traceback.documents.provider:LOCAL}") String provider,
            @Value("${traceback.documents.max-file-size-bytes}") int maximum,
            @Value("${traceback.aws.s3-kms-key:}") String kmsKey,
            @Value("${traceback.runtime:api}") String runtime) {
        var providers = new HashMap<String, DocumentStorage>();
        if (local.getIfAvailable() != null) providers.put("LOCAL", local.getObject());
        if (s3.getIfAvailable() != null) providers.put("S3", new S3DocumentStorage(s3.getObject(), bucket, maximum, runtime.equals("worker"), kmsKey));
        return new RoutingDocumentStorage(providers, provider);
    }
}
