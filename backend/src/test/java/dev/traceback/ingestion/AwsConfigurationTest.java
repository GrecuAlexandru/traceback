package dev.traceback.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class AwsConfigurationTest {
    final AwsConfiguration configuration = new AwsConfiguration();
    @Test void cloudClientsBuildWithoutResolvingCredentialsOrContactingAws() {
        var e = new MockEnvironment().withProperty("traceback.aws.mode", "aws").withProperty("traceback.aws.region", "eu-west-1");
        try (var s3 = configuration.s3Client(e); var sqs = configuration.sqsClient(e)) {
            assertEquals("https://s3.eu-west-1.amazonaws.com", s3.serviceClientConfiguration().endpointOverride().orElseThrow().toString());
            assertEquals("https://sqs.eu-west-1.amazonaws.com", sqs.serviceClientConfiguration().endpointOverride().orElseThrow().toString());
        }
    }
    @Test void cloudModeRequiresRegionAndForbidsEndpointInjection() {
        var e = new MockEnvironment().withProperty("traceback.aws.mode", "aws");
        assertThrows(IllegalStateException.class, () -> configuration.s3Client(e));
        e.withProperty("traceback.aws.region", "eu-west-1").withProperty("traceback.aws.endpoint", "http://localhost:4566");
        assertThrows(IllegalArgumentException.class, () -> configuration.s3Client(e));
        e.withProperty("traceback.aws.endpoint", "").withProperty("AWS_ENDPOINT_URL", "http://attacker.test");
        assertThrows(IllegalArgumentException.class, () -> configuration.sqsClient(e));
    }
    @Test void emulatorModeRejectsRemoteEndpointsAndUnknownModes() {
        var e = new MockEnvironment().withProperty("traceback.aws.endpoint", "https://s3.amazonaws.com");
        assertThrows(IllegalArgumentException.class, () -> configuration.s3Client(e));
        e.withProperty("traceback.aws.mode", "typo");
        assertThrows(IllegalArgumentException.class, () -> configuration.sqsClient(e));
    }
}
