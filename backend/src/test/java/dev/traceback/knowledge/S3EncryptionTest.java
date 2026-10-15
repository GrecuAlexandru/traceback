package dev.traceback.knowledge;

import dev.traceback.knowledge.storage.S3DocumentStorage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.sync.RequestBody;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3EncryptionTest {
    @Test void kmsUploadPreservesConditionalCreationAndIntegrity() throws Exception {
        var client = mock(S3Client.class);
        var key = "arn:aws:kms:eu-west-1:123456789012:key/synthetic-key";
        new S3DocumentStorage(client, "synthetic", 100, false, key).put(UUID.randomUUID(), new byte[]{1, 2, 3});
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertEquals(key, request.getValue().ssekmsKeyId());
        assertEquals(ServerSideEncryption.AWS_KMS, request.getValue().serverSideEncryption());
        assertEquals("*", request.getValue().ifNoneMatch());
        assertNotNull(request.getValue().checksumSHA256());
    }
}
