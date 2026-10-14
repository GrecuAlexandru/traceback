package dev.traceback.indexing;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class OllamaProviderTest {
    IndexSettings settings(){return new IndexSettings(true,"nomic-embed-text:v1.5","sha256:"+"1".repeat(64),3,1600,160,512,1000000,200,90,30,600,5);}
    void respond(com.sun.net.httpserver.HttpExchange e,int status,String text)throws java.io.IOException{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();}
    @Test void modelDigestMismatchPreventsDocumentTransmission()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var requests=new AtomicInteger();
        server.createContext("/api/tags",e->respond(e,200,"{\"models\":[]}"));server.createContext("/api/embed",e->{requests.incrementAndGet();respond(e,200,"{}");});server.start();
        try{var provider=new OllamaEmbeddingProvider(settings(),"http://127.0.0.1:"+server.getAddress().getPort(),1000);
            assertEquals("MODEL_INCOMPATIBLE",assertThrows(EmbeddingFailure.class,()->provider.documents(java.util.List.of("synthetic"))).code());assertEquals(0,requests.get());}
        finally{server.stop(0);}
    }
    @Test void timeoutIsBoundedAndRawDependencyErrorsAreSanitized()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var release=new CountDownLatch(1);
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()){
            server.setExecutor(threads);server.createContext("/api/tags",e->respond(e,200,"{\"models\":[{\"name\":\"nomic-embed-text:v1.5\",\"digest\":\""+"1".repeat(64)+"\"}]}"));
            server.createContext("/api/embed",e->{try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}e.close();});server.start();
            try{var provider=new OllamaEmbeddingProvider(settings(),"http://127.0.0.1:"+server.getAddress().getPort(),100);long start=System.nanoTime();
                var failure=assertThrows(EmbeddingFailure.class,()->provider.query("synthetic"));assertEquals("EMBEDDING_UNAVAILABLE",failure.code());assertEquals(failure.code(),failure.getMessage());assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<3);}
            finally{release.countDown();server.stop(0);}
        }
    }
    @Test void remoteEndpointIsRejected(){assertThrows(IllegalArgumentException.class,()->new OllamaEmbeddingProvider(settings(),"https://api.example.com",1000));}
    @Test void cloudBackedModelIsRejectedBeforeSendingText()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var requests=new AtomicInteger();
        server.createContext("/api/tags",e->respond(e,200,"{\"models\":[{\"name\":\"nomic-embed-text:v1.5\",\"digest\":\""+"1".repeat(64)+"\",\"remote_host\":\"https://cloud.example.com\",\"remote_model\":\"remote\"}]}"));
        server.createContext("/api/embed",e->{requests.incrementAndGet();respond(e,200,"{}");});server.start();
        try{var provider=new OllamaEmbeddingProvider(settings(),"http://127.0.0.1:"+server.getAddress().getPort(),1000);assertEquals("MODEL_INCOMPATIBLE",assertThrows(EmbeddingFailure.class,()->provider.query("synthetic")).code());assertEquals(0,requests.get());}
        finally{server.stop(0);}
    }
    @Test void stalledResponseBodyAlsoHonorsTimeout()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var release=new CountDownLatch(1);
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()){
            server.setExecutor(threads);server.createContext("/api/tags",e->respond(e,200,"{\"models\":[{\"name\":\"nomic-embed-text:v1.5\",\"digest\":\""+"1".repeat(64)+"\"}]}"));
            server.createContext("/api/embed",e->{e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(200,1000);e.getResponseBody().flush();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}e.close();});server.start();
            try{var provider=new OllamaEmbeddingProvider(settings(),"http://127.0.0.1:"+server.getAddress().getPort(),100);
                var future=threads.submit(()->assertThrows(EmbeddingFailure.class,()->provider.query("synthetic")).code());
                assertEquals("EMBEDDING_UNAVAILABLE",future.get(3,TimeUnit.SECONDS));}
            finally{release.countDown();server.stop(0);}
        }
    }
}
