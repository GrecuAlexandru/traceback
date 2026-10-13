package dev.traceback.knowledge;

import dev.traceback.knowledge.storage.LocalDocumentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalDocumentStorageTest {
    @TempDir Path temporary;
    Path realRoot() throws IOException { return temporary.toRealPath().resolve("objects"); }

    @Test void objectsAreOpaquePrivateBoundedAndNeverOverwrite() throws Exception {
        Path root = realRoot(); UUID key = UUID.randomUUID();
        try (var adapter = new LocalDocumentStorage(root.toString())) {
            adapter.put(key, "original".getBytes());
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(root.resolve(key+".blob")));
            assertThrows(IOException.class, () -> adapter.put(key,"overwrite".getBytes()));
            assertArrayEquals("original".getBytes(), adapter.read(key,8));
            assertThrows(IOException.class, () -> adapter.read(key,7));
            adapter.delete(key); adapter.delete(key);
            assertThrows(IOException.class, () -> adapter.read(key,10));
        }
    }
    @Test void symlinkObjectsCannotReadOrOverwriteUnrelatedFiles() throws Exception {
        Path root = realRoot(); Path unrelated = temporary.toRealPath().resolve("unrelated.txt");
        Files.writeString(unrelated,"private"); UUID key = UUID.randomUUID();
        try (var adapter = new LocalDocumentStorage(root.toString())) {
            Files.createSymbolicLink(root.resolve(key+".blob"),unrelated);
            assertThrows(IOException.class, () -> adapter.read(key,100));
            assertThrows(IOException.class, () -> adapter.put(key,"bad".getBytes()));
            adapter.delete(key); assertEquals("private",Files.readString(unrelated));
            Files.createSymbolicLink(root.resolve(key+".part"),unrelated);
            assertThrows(IOException.class, () -> adapter.put(key,"bad".getBytes()));
            adapter.delete(key); assertEquals("private",Files.readString(unrelated));
        }
    }
    @Test void unsafeDirectoriesAndSecondRuntimeAreRejected() throws Exception {
        assertThrows(IOException.class, () -> new LocalDocumentStorage("relative/path"));
        assertThrows(IOException.class, () -> new LocalDocumentStorage(Path.of("").toAbsolutePath().resolve("unsafe-docs").toString()));
        Path link = temporary.toRealPath().resolve("link"); Files.createSymbolicLink(link,temporary.toRealPath());
        assertThrows(IOException.class, () -> new LocalDocumentStorage(link.resolve("objects").toString()));
        try (var adapter = new LocalDocumentStorage(realRoot().toString())) {
            assertThrows(IOException.class, () -> new LocalDocumentStorage(realRoot().toString()));
        }
    }
    @Test void nonemptyUnmanagedDirectoryIsRejectedWithoutChangingItsContentsOrPermissions() throws Exception {
        Path root = realRoot(); Files.createDirectory(root);
        Files.writeString(root.resolve("personal.txt"),"preserve");
        var before = Files.getPosixFilePermissions(root);
        assertThrows(IOException.class, () -> new LocalDocumentStorage(root.toString()));
        assertEquals(before,Files.getPosixFilePermissions(root));
        assertEquals("preserve",Files.readString(root.resolve("personal.txt")));
        assertFalse(Files.exists(root.resolve(".traceback-storage")));
    }

}
