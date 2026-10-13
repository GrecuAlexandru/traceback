package dev.traceback.knowledge;

import java.io.IOException;
import java.nio.file.Files;

/** Each Spring context gets disposable private storage, never a personal directory. */
public final class TestDocumentStorage {
    private TestDocumentStorage() {}
    public static String directory() {
        try { return Files.createTempDirectory("traceback-test-documents-").toRealPath().toString(); }
        catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }
}
