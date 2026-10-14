package dev.traceback.knowledge.storage;

import dev.traceback.knowledge.DocumentUploadValidator;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Single bounded PUT is atomic in S3: no multipart fragments or caller-chosen paths. */
public class S3DocumentStorage implements DocumentStorage {
    private final S3Client client;
    private final String bucket;
    private final int maximumBytes;
    private final boolean readOnly;
    public S3DocumentStorage(S3Client client, String bucket, int maximumBytes, boolean readOnly) {
        this.client = client; this.bucket = bucket; this.maximumBytes = maximumBytes; this.readOnly = readOnly;
    }
    @Override public String provider() { return "S3"; }
    static String key(UUID key) { return "documents/" + key; }
    @Override public void put(UUID key, byte[] content) throws IOException {
        if (readOnly) throw new IOException("Worker storage is read-only");
        if (content.length < 1 || content.length > maximumBytes) throw new StorageFailure("CONTENT_LIMIT");
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key(key)).ifNoneMatch("*")
                    .contentType("application/octet-stream").serverSideEncryption(ServerSideEncryption.AES256)
                    .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(DocumentUploadValidator.checksum(content))))
                    .build(), RequestBody.fromBytes(content));
        } catch (RuntimeException ex) { throw new IOException("Object upload unavailable"); }
    }
    @Override public byte[] read(UUID key, long maximumBytes) throws IOException {
        if (maximumBytes < 1 || maximumBytes > this.maximumBytes) throw new StorageFailure("CONTENT_LIMIT");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try (var input = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key(key)).build())) {
            try {
                if (input.response().contentLength() > maximumBytes) throw new StorageFailure("CONTENT_LIMIT");
                var output = new ByteArrayOutputStream((int)Math.min(input.response().contentLength(), maximumBytes));
                byte[] buffer = new byte[8192];
                int count;
                while (true) {
                    if (System.nanoTime() > deadline) throw new IOException("Object read deadline exceeded");
                    count = input.read(buffer);
                    if (count == -1) break;
                    if ((long)output.size() + count > maximumBytes) throw new StorageFailure("CONTENT_LIMIT");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            } catch (IOException | RuntimeException ex) {
                input.abort(); // Never drain an oversized, failed or stalled response during cleanup.
                throw ex;
            }
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) throw new StorageFailure("CONTENT_MISSING");
            throw new IOException("Object read unavailable");
        } catch (RuntimeException ex) { throw new IOException("Object read unavailable"); }
    }
    @Override public void delete(UUID key) throws IOException {
        if (readOnly) throw new IOException("Worker storage is read-only");
        try { client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(key)).build()); }
        catch (RuntimeException ex) { throw new IOException("Object cleanup unavailable"); }
    }
}
