package dev.traceback.indexing;

import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.*;
import org.springframework.ai.ollama.management.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

@Component
public class OllamaEmbeddingProvider implements EmbeddingProvider {
    private final IndexSettings settings;
    private final OllamaEmbeddingModel model;
    private final OllamaEmbeddingOptions options;
    private final RestClient client;
    public OllamaEmbeddingProvider(IndexSettings settings,@Value("${traceback.embedding.endpoint:http://127.0.0.1:11435}") String endpoint,
                                   @Value("${traceback.embedding.timeout-ms:20000}") int timeout) {
        URI uri=URI.create(endpoint);
        if(!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1","localhost","ollama").contains(uri.getHost())
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !Set.of("","/").contains(uri.getPath())
                || timeout<100 || timeout>20000) throw new IllegalArgumentException("Local embedding endpoint required");
        this.settings=settings;
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofMillis(timeout));
        var builder=RestClient.builder().baseUrl(endpoint).requestFactory(factory);
        client=builder.build();
        options=OllamaEmbeddingOptions.builder().model(settings.model()).truncate(false).dimensions(settings.dimensions()).numCtx(8192).numThread(2).numGPU(0).build();
        model=OllamaEmbeddingModel.builder().ollamaApi(OllamaApi.builder().baseUrl(endpoint).restClientBuilder(builder).build())
                .options(options).modelManagementOptions(ModelManagementOptions.builder().pullModelStrategy(PullModelStrategy.NEVER).build()).build();
    }
    private void compatible() {
        @SuppressWarnings("unchecked") Map<String,Object> tags=client.get().uri("/api/tags").retrieve().body(Map.class);
        if(tags==null || !(tags.get("models") instanceof List<?> models) || models.stream().noneMatch(v -> v instanceof Map<?,?> m
                && settings.model().equals(m.get("name")) && settings.digest().equals("sha256:"+m.get("digest"))
                && !m.containsKey("remote_host") && !m.containsKey("remote_model")))
            throw new EmbeddingFailure("MODEL_INCOMPATIBLE");
    }
    private List<float[]> embed(List<String> texts,String prefix) {
        if(!settings.enabled()) throw new EmbeddingFailure("EMBEDDING_DISABLED");
        if(texts.isEmpty() || texts.size()>8 || texts.stream().anyMatch(t->t==null || t.isBlank() || StructureChunker.points(t)>1600))
            throw new EmbeddingFailure("EMBEDDING_INPUT_LIMIT");
        try {
            compatible();
            var response=model.call(new EmbeddingRequest(texts.stream().map(t->prefix+t).toList(),options));
            if(response.getResults().size()!=texts.size()) throw new EmbeddingFailure("EMBEDDING_BATCH");
            var vectors=new ArrayList<float[]>();
            for(int i=0;i<texts.size();i++) {
                var result=response.getResults().get(i);
                if(result.getIndex()!=i) throw new EmbeddingFailure("EMBEDDING_BATCH");
                EmbeddingProvider.validate(result.getOutput(),settings.dimensions());vectors.add(result.getOutput());
            }
            compatible();return List.copyOf(vectors);
        } catch(EmbeddingFailure ex) {throw ex;}
        catch(RuntimeException ex) {throw new EmbeddingFailure("EMBEDDING_UNAVAILABLE");}
    }
    @Override public List<float[]> documents(List<String> texts) {return embed(texts,"search_document: ");}
    @Override public float[] query(String query) {return embed(List.of(query),"search_query: ").getFirst();}
}
