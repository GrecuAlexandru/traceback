package dev.traceback.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record ChatSettings(boolean enabled,String model,String digest,int contextTokens,int outputTokens,double temperature,
                           int timeoutSeconds,int concurrent,int maxQuestionBytes,int promptBytes,int evidenceBytes,
                           int historyBytes,int historyMessages,int retrievalChunks,int batchSize,int threads) {
    public ChatSettings(@Value("${traceback.chat.enabled:false}") boolean enabled,
                        @Value("${traceback.chat.model:qwen2.5:7b}") String model,
                        @Value("${traceback.chat.digest:sha256:0000000000000000000000000000000000000000000000000000000000000000}") String digest,
                        @Value("${traceback.chat.context-tokens:8192}") int contextTokens,
                        @Value("${traceback.chat.output-tokens:512}") int outputTokens,
                        @Value("${traceback.chat.temperature:0.0}") double temperature,
                        @Value("${traceback.chat.timeout-seconds:120}") int timeoutSeconds,
                        @Value("${traceback.chat.concurrent:2}") int concurrent,
                        @Value("${traceback.chat.max-question-bytes:1000}") int maxQuestionBytes,
                        @Value("${traceback.chat.prompt-bytes:6000}") int promptBytes,
                        @Value("${traceback.chat.evidence-bytes:3000}") int evidenceBytes,
                        @Value("${traceback.chat.history-bytes:1500}") int historyBytes,
                        @Value("${traceback.chat.history-messages:8}") int historyMessages,
                        @Value("${traceback.chat.retrieval-chunks:4}") int retrievalChunks,
                        @Value("${traceback.chat.batch-size:64}") int batchSize,
                        @Value("${traceback.chat.threads:4}") int threads) {
        if(!model.matches("[a-zA-Z0-9._:/-]{1,128}") || !digest.matches("sha256:[0-9a-f]{64}")
            || (enabled && digest.equals("sha256:"+"0".repeat(64))) || contextTokens<4096 || contextTokens>32768
            || outputTokens<64 || outputTokens>2048 || !Double.isFinite(temperature) || temperature<0 || temperature>0.5
            || timeoutSeconds<1 || timeoutSeconds>120 || concurrent<1 || concurrent>4
            || maxQuestionBytes<1 || maxQuestionBytes>4000 || promptBytes<2000 || promptBytes>16000
            || promptBytes+outputTokens+1024>contextTokens || evidenceBytes<1 || evidenceBytes>8000
            || batchSize<1 || batchSize>128 || threads<1 || threads>4
            || historyBytes<0 || historyBytes>4000 || historyMessages<0 || historyMessages>16 || retrievalChunks<1 || retrievalChunks>8)
            throw new IllegalArgumentException("Invalid chat configuration");
        this.enabled=enabled;this.model=model;this.digest=digest;this.contextTokens=contextTokens;this.outputTokens=outputTokens;
        this.temperature=temperature;this.timeoutSeconds=timeoutSeconds;this.concurrent=concurrent;this.maxQuestionBytes=maxQuestionBytes;
        this.promptBytes=promptBytes;this.evidenceBytes=evidenceBytes;this.historyBytes=historyBytes;this.historyMessages=historyMessages;this.retrievalChunks=retrievalChunks;this.batchSize=batchSize;this.threads=threads;
    }
}
