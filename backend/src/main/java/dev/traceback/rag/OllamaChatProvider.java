package dev.traceback.rag;

import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.*;
import org.springframework.ai.ollama.management.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.*;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

@Component
public class OllamaChatProvider implements ChatProvider {
    private final ChatSettings settings;
    private final RestClient client;
    private final OllamaChatModel model;
    public OllamaChatProvider(ChatSettings settings,@Value("${traceback.chat.endpoint:http://127.0.0.1:11436}") String endpoint) {
        URI uri=URI.create(endpoint);
        if(!"http".equals(uri.getScheme()) || !Set.of("localhost","127.0.0.1","chat-ollama").contains(uri.getHost())
            || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !Set.of("","/").contains(uri.getPath()))
            throw new IllegalArgumentException("Local chat endpoint required");
        this.settings=settings;
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(settings.timeoutSeconds()));
        var builder=RestClient.builder().baseUrl(endpoint).requestFactory(factory)
            .defaultStatusHandler(HttpStatusCode::isError,(request,response)-> {
                if(response.getStatusCode().is5xxServerError())throw new HttpServerErrorException(response.getStatusCode());
                throw new HttpClientErrorException(response.getStatusCode());
            }).requestInterceptor((request,body,execution)-> {
            var response=execution.execute(request,body);
            // Bound provider-controlled response allocations, including tags and show metadata.
            return new ClientHttpResponse() {
                public HttpStatusCode getStatusCode() throws IOException {return response.getStatusCode();}
                public String getStatusText() throws IOException {return response.getStatusText();}
                public HttpHeaders getHeaders() {return response.getHeaders();}
                public void close() {response.close();}
                public InputStream getBody() throws IOException {
                    return new FilterInputStream(response.getBody()) {
                        private int read;
                        private void counted(int n) throws IOException {if(n>0 && (read+=n)>65536)throw new IOException("Provider response limit");}
                        @Override public int read() throws IOException {int n=super.read();counted(n<0?0:1);return n;}
                        @Override public int read(byte[] b,int off,int len) throws IOException {int n=in.read(b,off,Math.min(len,65537-read));counted(n);return n;}
                    };
                }
            };
        });
        client=builder.build();
        var options=OllamaChatOptions.builder().model(settings.model()).temperature(settings.temperature()).numCtx(settings.contextTokens())
            .numPredict(settings.outputTokens()).numBatch(settings.batchSize()).numThread(settings.threads()).numGPU(0).useMMap(true).useMLock(false).format(outputSchema()).keepAlive("0s").truncate(false).build();
        model=OllamaChatModel.builder().ollamaApi(OllamaApi.builder().baseUrl(endpoint).restClientBuilder(builder).build()).options(options)
            .toolCallingManager(new org.springframework.ai.model.tool.ToolCallingManager() {
                public List<org.springframework.ai.tool.definition.ToolDefinition> resolveToolDefinitions(org.springframework.ai.model.tool.ToolCallingChatOptions options) {return List.of();}
                public org.springframework.ai.model.tool.ToolExecutionResult executeToolCalls(Prompt prompt,org.springframework.ai.chat.model.ChatResponse response) {throw ChatFailure.provider("MALFORMED_OUTPUT");}
            })
            .observationRegistry(io.micrometer.observation.ObservationRegistry.NOOP)
            .modelManagementOptions(ModelManagementOptions.builder().pullModelStrategy(PullModelStrategy.NEVER).build())
            .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build();
    }
    private Map<String,Object> outputSchema() {
        var references=new ArrayList<String>();for(int i=1;i<=settings.retrievalChunks();i++)references.add("S"+i);
        var answer=Map.of("type","object","additionalProperties",false,"required",List.of("status","answer","references"),
            "properties",Map.of("status",Map.of("type","string","enum",List.of("ANSWERED")),
                "answer",Map.of("type","string"),"references",Map.of("type","array","minItems",1,"maxItems",settings.retrievalChunks(),
                    "items",Map.of("type","string","enum",references))));
        var abstention=Map.of("type","object","additionalProperties",false,"required",List.of("status","answer","references"),
            "properties",Map.of("status",Map.of("type","string","enum",List.of("INSUFFICIENT_EVIDENCE")),
                "answer",Map.of("type","string"),"references",Map.of("type","array","maxItems",0,"items",Map.of("type","string"))));
        return Map.of("oneOf",List.of(answer,abstention));
    }
    private void compatible() {
        @SuppressWarnings("unchecked") Map<String,Object> tags=client.get().uri("/api/tags").retrieve().body(Map.class);
        if(tags==null || !(tags.get("models") instanceof List<?> models) || models.stream().noneMatch(v->v instanceof Map<?,?> m
            && settings.model().equals(m.get("name")) && settings.digest().equals("sha256:"+m.get("digest"))
            && !m.containsKey("remote_host") && !m.containsKey("remote_model"))) throw ChatFailure.provider("MODEL_INCOMPATIBLE");
        @SuppressWarnings("unchecked") Map<String,Object> show=client.post().uri("/api/show").body(Map.of("model",settings.model())).retrieve().body(Map.class);
        if(show==null || show.containsKey("remote_host") || show.containsKey("remote_model")
            || !(show.get("capabilities") instanceof List<?> capabilities) || !capabilities.contains("completion")
            || !(show.get("model_info") instanceof Map<?,?> info) || info.entrySet().stream().noneMatch(e->e.getKey().toString().endsWith(".context_length")
                && e.getValue() instanceof Number n && n.longValue()>=settings.contextTokens())) throw ChatFailure.provider("MODEL_INCOMPATIBLE");
    }
    private boolean transientStatus(Throwable failure) {
        for(int depth=0;failure!=null && depth<8;depth++,failure=failure.getCause())
            if(failure instanceof HttpServerErrorException status && Set.of(502,503).contains(status.getStatusCode().value()))return true;
        return false;
    }
    @Override public Result generate(String system,String data) {
        if(!settings.enabled()) throw ChatFailure.provider("CHAT_DISABLED");
        if(RagContext.bytes(system)+RagContext.bytes(data)>settings.promptBytes()) throw ChatFailure.provider("CONTEXT_LIMIT");
        // Only one retry for an explicit HTTP 503/502, with no conversation reservation repeated.
        for(int attempt=0;attempt<2;attempt++) {
            try {
                compatible();var result=model.call(new Prompt(List.of(new SystemMessage(system),new UserMessage(data))));
                if(result==null || result.getResults().size()!=1 || result.getResult().getOutput().hasToolCalls()) throw ChatFailure.provider("MALFORMED_OUTPUT");
                String content=result.getResult().getOutput().getText();
                if(content==null || content.isBlank() || RagContext.bytes(content)>8000) throw ChatFailure.provider("MALFORMED_OUTPUT");
                if("length".equals(result.getResult().getMetadata().getFinishReason())) throw ChatFailure.provider("OUTPUT_LIMIT");
                compatible();var usage=result.getMetadata().getUsage();
                return new Result(content,usage==null?null:usage.getPromptTokens(),usage==null?null:usage.getCompletionTokens());
            } catch(HttpServerErrorException ex) {
                if(attempt==0 && Set.of(502,503).contains(ex.getStatusCode().value()))continue;
                throw ChatFailure.provider("MODEL_UNAVAILABLE");
            } catch(ChatFailure ex) {throw ex;}
            catch(RuntimeException ex) {
                if(attempt==0 && transientStatus(ex))continue;
                throw ChatFailure.provider("MODEL_UNAVAILABLE");
            }
        }
        throw ChatFailure.provider("MODEL_UNAVAILABLE");
    }
}
