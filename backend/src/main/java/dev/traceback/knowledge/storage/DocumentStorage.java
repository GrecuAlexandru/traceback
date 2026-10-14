package dev.traceback.knowledge.storage;

import java.io.IOException;
import java.util.UUID;

/** Internal capability. Keys come only from authorized persisted revisions, never HTTP input. */
public interface DocumentStorage {
    default String provider() { return "LOCAL"; }
    default byte[] read(String provider, UUID key, long maximumBytes) throws IOException {
        if (!provider().equals(provider)) throw new IOException("Storage provider unavailable");
        return read(key, maximumBytes);
    }
    default void delete(String provider, UUID key) throws IOException {
        if (!provider().equals(provider)) throw new IOException("Storage provider unavailable");
        delete(key);
    }
    void put(UUID key, byte[] content) throws IOException;
    byte[] read(UUID key, long maximumBytes) throws IOException;
    void delete(UUID key) throws IOException;
}
