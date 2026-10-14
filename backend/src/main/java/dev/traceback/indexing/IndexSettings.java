package dev.traceback.indexing;

import dev.traceback.knowledge.DocumentUploadValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

@Component
public record IndexSettings(boolean enabled, String model, String digest, int dimensions, int chunkSize, int overlap,
                            int maxChunks, int maxText, int maxPages, int leaseSeconds, int heartbeatSeconds, int deadlineSeconds, int maxAttempts) {
    public IndexSettings(@Value("${traceback.indexing.enabled:false}") boolean enabled,
                         @Value("${traceback.embedding.model:nomic-embed-text:v1.5}") String model,
                         @Value("${traceback.embedding.digest:sha256:0000000000000000000000000000000000000000000000000000000000000000}") String digest,
                         @Value("${traceback.embedding.dimensions:768}") int dimensions,
                         @Value("${traceback.indexing.chunk-size:1600}") int chunkSize,
                         @Value("${traceback.indexing.overlap:160}") int overlap,
                         @Value("${traceback.indexing.max-chunks:512}") int maxChunks,
                         @Value("${traceback.extraction.max-text:1000000}") int maxText,
                         @Value("${traceback.extraction.max-pages:200}") int maxPages,
                         @Value("${traceback.indexing.lease-seconds:90}") int leaseSeconds,
                         @Value("${traceback.indexing.heartbeat-seconds:30}") int heartbeatSeconds,
                         @Value("${traceback.indexing.deadline-seconds:600}") int deadlineSeconds,
                         @Value("${traceback.indexing.max-attempts:5}") int maxAttempts) {
        if (!model.matches("[a-zA-Z0-9._:/-]{1,128}") || !digest.matches("sha256:[0-9a-f]{64}") || dimensions<1 || dimensions>2000
                || chunkSize<200 || chunkSize>1600 || overlap<0 || overlap>chunkSize/4 || maxChunks<1 || maxChunks>512
                || maxText<1 || maxText>1000000 || maxPages<1 || maxPages>200 || leaseSeconds<60 || leaseSeconds>300
                || heartbeatSeconds<1 || heartbeatSeconds>leaseSeconds/3 || deadlineSeconds<60 || deadlineSeconds>600
                || maxAttempts<1 || maxAttempts>10 || (enabled && digest.equals("sha256:"+"0".repeat(64))))
            throw new IllegalArgumentException("Invalid indexing configuration");
        this.enabled=enabled;this.model=model;this.digest=digest;this.dimensions=dimensions;this.chunkSize=chunkSize;
        this.overlap=overlap;this.maxChunks=maxChunks;this.maxText=maxText;this.maxPages=maxPages;this.leaseSeconds=leaseSeconds;
        this.heartbeatSeconds=heartbeatSeconds;this.deadlineSeconds=deadlineSeconds;this.maxAttempts=maxAttempts;
    }
    public String configuration() {
        return DocumentUploadValidator.checksum(("pdfbox-3.0.8-text-v1|structure-v1|"+model+"|"+digest+"|"+dimensions+"|"+chunkSize+"|"+overlap
                +"|"+maxChunks+"|"+maxText+"|"+maxPages+"|search_document:|search_query:").getBytes(StandardCharsets.UTF_8));
    }
}
