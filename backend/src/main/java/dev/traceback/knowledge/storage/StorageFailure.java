package dev.traceback.knowledge.storage;

import java.io.IOException;

/** Sanitized permanent failure, suitable for durable diagnostics. */
public class StorageFailure extends IOException {
    private final String code;
    public StorageFailure(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
