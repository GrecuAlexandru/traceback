package dev.traceback.ingestion;

import java.util.List;

public interface IngestionQueue {
    void send(String body);
    List<Delivery> receive();
    void acknowledge(String receipt);
    void defer(String receipt,int seconds);
    boolean ready();
    record Delivery(String body,String receipt) {}
}
