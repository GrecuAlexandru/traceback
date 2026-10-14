package dev.traceback.rag;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OllamaChatProviderTest {
    ChatSettings settings() {return new ChatSettings(true,"qwen2.5:1.5b","sha256:"+"1".repeat(64),8192,512,0,1,2,1000,6000,3000,1500,8,4,64,4);}
    void respond(HttpExchange e,int status,String text) throws java.io.IOException {byte[] b=text.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(status,b.length);e.getResponseBody().write(b);e.close();}
    String tags(boolean remote) {return "{\"models\":[{\"name\":\"qwen2.5:1.5b\",\"digest\":\""+"1".repeat(64)+"\""+(remote?",\"remote_host\":\"cloud\"":"")+"}]}";}
    @Test void digestAndCloudChecksBlockTransmission() throws Exception {
        for(String body:new String[]{"{\"models\":[]}",tags(true)}) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();server.createContext("/api/tags",e->respond(e,200,body));
            server.createContext("/api/chat",e->{calls.incrementAndGet();respond(e,200,"{}");});server.start();
            try {var provider=new OllamaChatProvider(settings(),"http://127.0.0.1:"+server.getAddress().getPort());assertEquals("MODEL_INCOMPATIBLE",assertThrows(ChatFailure.class,()->provider.generate("policy","synthetic")).code());assertEquals(0,calls.get());}
            finally {server.stop(0);}
        }
    }
    @Test void incompatibleContextAndEmbeddingOnlyModelBlockTransmission() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api/tags",e->respond(e,200,tags(false)));
        server.createContext("/api/show",e->respond(e,200,"{\"capabilities\":[\"embedding\"],\"model_info\":{\"qwen2.context_length\":2048}}"));server.start();
        try {assertEquals("MODEL_INCOMPATIBLE",assertThrows(ChatFailure.class,()->new OllamaChatProvider(settings(),"http://127.0.0.1:"+server.getAddress().getPort()).generate("policy","synthetic")).code());}
        finally {server.stop(0);}
    }
    @Test void explicit503RetriesAreBoundedAndErrorsAreSanitized() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();server.createContext("/api/tags",e->{calls.incrementAndGet();respond(e,503,"private prompt token");});server.start();
        try {var failure=assertThrows(ChatFailure.class,()->new OllamaChatProvider(settings(),"http://127.0.0.1:"+server.getAddress().getPort()).generate("policy","synthetic"));assertEquals("MODEL_UNAVAILABLE",failure.code());assertEquals(2,calls.get());}
        finally {server.stop(0);}
    }
    @Test void springAiRoundtripRecordsUsageAndBoundedRetryAtInferenceEndpoint() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        var requestBody=new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/api/tags",e->respond(e,200,tags(false)));
        server.createContext("/api/show",e->respond(e,200,"{\"capabilities\":[\"completion\"],\"model_info\":{\"qwen2.context_length\":32768}}"));
        server.createContext("/api/chat",e->{requestBody.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));if(calls.incrementAndGet()==1)respond(e,503,"synthetic unavailable");else respond(e,200,
            "{\"model\":\"qwen2.5:1.5b\",\"message\":{\"role\":\"assistant\",\"content\":\"synthetic output\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":12,\"eval_count\":4}");});server.start();
        try {var result=new OllamaChatProvider(settings(),"http://127.0.0.1:"+server.getAddress().getPort()).generate("policy","synthetic");assertEquals("synthetic output",result.content());assertEquals(12,result.inputTokens());assertEquals(4,result.outputTokens());assertEquals(2,calls.get());
            var tree=tools.jackson.databind.json.JsonMapper.builder().build().readTree(requestBody.get());
            assertEquals(64,tree.get("options").get("num_batch").asInt());assertEquals(4,tree.get("options").get("num_thread").asInt());
            assertEquals(8192,tree.get("options").get("num_ctx").asInt());assertEquals(512,tree.get("options").get("num_predict").asInt());
            assertEquals(0,tree.get("options").get("num_gpu").asInt());assertTrue(tree.get("options").get("use_mmap").asBoolean());
            assertFalse(tree.get("options").get("use_mlock").asBoolean());assertFalse(tree.get("stream").asBoolean());}
        finally {server.stop(0);}
    }
    @Test void slowHeadersAndStalledBodyHonorTransportTimeout() throws Exception {
        for(boolean sendHeaders:new boolean[]{false,true}) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var release=new java.util.concurrent.CountDownLatch(1);
            try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                server.setExecutor(executor);server.createContext("/api/tags",e->{if(sendHeaders){e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(200,2000);e.getResponseBody().flush();}
                    try {release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}e.close();});server.start();
                try {var future=executor.submit(()->assertThrows(ChatFailure.class,()->new OllamaChatProvider(settings(),"http://127.0.0.1:"+server.getAddress().getPort()).generate("policy","synthetic")).code());assertEquals("MODEL_UNAVAILABLE",future.get(3,java.util.concurrent.TimeUnit.SECONDS));}
                finally {release.countDown();server.stop(0);}
            }
        }
    }
    @Test void oversizedProviderMetadataFailsWithoutAllocatingUnboundedBody() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api/tags",e->respond(e,200,"{\"padding\":\""+"x".repeat(100000)+"\"}"));server.start();
        try {assertEquals("MODEL_UNAVAILABLE",assertThrows(ChatFailure.class,()->new OllamaChatProvider(settings(),"http://127.0.0.1:"+server.getAddress().getPort()).generate("policy","synthetic")).code());}
        finally {server.stop(0);}
    }
    @Test void unsolicitedToolCallsAreRejectedWithoutExecution() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api/tags",e->respond(e,200,tags(false)));
        server.createContext("/api/show",e->respond(e,200,"{\"capabilities\":[\"completion\"],\"model_info\":{\"qwen2.context_length\":32768}}"));
        server.createContext("/api/chat",e->respond(e,200,"{\"model\":\"qwen2.5:1.5b\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"shell\",\"arguments\":{\"command\":\"synthetic\"}}}]},\"done\":true,\"done_reason\":\"stop\"}"));server.start();
        try {assertEquals("MALFORMED_OUTPUT",assertThrows(ChatFailure.class,()->new OllamaChatProvider(settings(),"http://127.0.0.1:"+server.getAddress().getPort()).generate("policy","synthetic")).code());}
        finally {server.stop(0);}
    }
    @Test void batchAndThreadSettingsRejectUnboundedResourceConfiguration() {
        assertThrows(IllegalArgumentException.class,()->new ChatSettings(true,"qwen2.5:7b","sha256:"+"1".repeat(64),8192,512,0,60,2,1000,6000,3000,1500,8,4,129,4));
        assertThrows(IllegalArgumentException.class,()->new ChatSettings(true,"qwen2.5:7b","sha256:"+"1".repeat(64),8192,512,0,60,2,1000,6000,3000,1500,8,4,64,5));
    }
    @Test void rejectsRemoteEndpointAndContextOverflow() {
        assertThrows(IllegalArgumentException.class,()->new OllamaChatProvider(settings(),"https://cloud.example"));
        assertEquals("CONTEXT_LIMIT",assertThrows(ChatFailure.class,()->new OllamaChatProvider(settings(),"http://localhost:11436").generate("x".repeat(6001),"data")).code());
    }
}
