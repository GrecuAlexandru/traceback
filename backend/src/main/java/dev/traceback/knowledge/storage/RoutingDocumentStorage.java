package dev.traceback.knowledge.storage;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

public class RoutingDocumentStorage implements DocumentStorage {
    private final Map<String, DocumentStorage> providers;
    private final String selected;
    public RoutingDocumentStorage(Map<String, DocumentStorage> providers, String selected) {
        this.providers = Map.copyOf(providers); this.selected = selected;
        if (!providers.containsKey(selected)) throw new IllegalArgumentException("Configured storage provider unavailable");
    }
    private DocumentStorage resolve(String provider) throws IOException {
        var storage = providers.get(provider);
        if (storage == null) throw new IOException("Storage provider unavailable");
        return storage;
    }
    @Override public String provider() { return selected; }
    @Override public void put(UUID key, byte[] content) throws IOException { resolve(selected).put(key, content); }
    @Override public byte[] read(UUID key, long maximumBytes) throws IOException { return resolve(selected).read(key, maximumBytes); }
    @Override public void delete(UUID key) throws IOException { resolve(selected).delete(key); }
    @Override public byte[] read(String provider, UUID key, long maximumBytes) throws IOException {
        // Keep the current-provider path through the public capability (also used by failure-injection tests).
        return selected.equals(provider) ? read(key, maximumBytes) : resolve(provider).read(key, maximumBytes);
    }
    @Override public void delete(String provider, UUID key) throws IOException {
        if (selected.equals(provider)) delete(key); else resolve(provider).delete(key);
    }
}
