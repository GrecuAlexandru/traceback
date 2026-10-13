package dev.traceback.knowledge.storage;

import java.io.IOException;
import java.util.UUID;

/** Internal capability. Keys come only from authorized persisted revisions, never HTTP input. */
public interface DocumentStorage {
    void put(UUID key, byte[] content) throws IOException;
    byte[] read(UUID key, long maximumBytes) throws IOException;
    void delete(UUID key) throws IOException;
}
