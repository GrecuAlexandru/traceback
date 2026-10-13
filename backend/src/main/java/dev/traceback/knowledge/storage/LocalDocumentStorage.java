package dev.traceback.knowledge.storage;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

@Component
public class LocalDocumentStorage implements DocumentStorage, AutoCloseable {
    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock lock;

    public LocalDocumentStorage(@Value("${traceback.documents.storage-directory}") String directory) throws IOException {
        Path configured = Path.of(directory);
        if (!configured.isAbsolute()) throw new IOException("Document storage must be an absolute private directory");
        root = configured.normalize();
        // Refuse symlinks in every existing path component, not just the final file.
        Path part = root.getRoot();
        for (Path segment : root) {
            part = part.resolve(segment);
            if (Files.isSymbolicLink(part)) throw new IOException("Storage directory must not contain symlinks");
        }
        // Locate a checkout even when launched from backend/; keep blobs outside it.
        for (Path cwd = Path.of("").toAbsolutePath(); cwd != null; cwd = cwd.getParent()) {
            if (Files.exists(cwd.resolve("AGENTS.md")) && root.startsWith(cwd)) {
                throw new IOException("Document storage must be outside the repository");
            }
        }
        for (Path ancestor = root; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor.resolve("AGENTS.md"))) throw new IOException("Document storage must be outside repository trees");
        }
        Files.createDirectories(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw new IOException("Invalid storage directory");
        Path marker = root.resolve(".traceback-storage");
        if (!Files.exists(marker, NOFOLLOW_LINKS)) {
            try (var entries = Files.list(root)) {
                if (entries.findAny().isPresent()) throw new IOException("Storage must be empty or an initialized Traceback directory");
            }
            Files.createFile(marker, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(marker, "traceback-private-documents-v1\n", WRITE, NOFOLLOW_LINKS);
        } else if (!Files.isRegularFile(marker, NOFOLLOW_LINKS)
                || Files.size(marker) > 100 || !Files.readString(marker).equals("traceback-private-documents-v1\n")) {
            throw new IOException("Invalid Traceback storage marker");
        }
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        lockChannel = FileChannel.open(root.resolve(".lock"), CREATE, WRITE, NOFOLLOW_LINKS);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("Storage directory is already in use");
        } catch (RuntimeException | IOException ex) {
            lockChannel.close();
            throw new IOException("Storage directory is already in use", ex);
        }
    }

    private Path object(UUID key) { return root.resolve(key.toString() + ".blob"); }
    private Path staging(UUID key) { return root.resolve(key.toString() + ".part"); }

    @Override
    public void put(UUID key, byte[] content) throws IOException {
        Path staged = staging(key);
        if (Files.exists(object(key), NOFOLLOW_LINKS)) throw new IOException("Object already exists");
        try (FileChannel channel = FileChannel.open(staged, java.util.Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        Files.move(staged, object(key), StandardCopyOption.ATOMIC_MOVE);
        // Sync directory metadata before the database advertises availability (POSIX adapter).
        try (FileChannel directory = FileChannel.open(root, READ)) { directory.force(true); }
    }

    @Override
    public byte[] read(UUID key, long maximumBytes) throws IOException {
        if (!Files.isRegularFile(object(key), NOFOLLOW_LINKS)) throw new IOException("Invalid storage object");
        try (var input = Files.newInputStream(object(key), READ, NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(Math.toIntExact(maximumBytes + 1));
            if (bytes.length > maximumBytes) throw new IOException("Storage object exceeds limit");
            return bytes;
        }
    }

    @Override
    public void delete(UUID key) throws IOException {
        Files.deleteIfExists(staging(key));
        Files.deleteIfExists(object(key));
    }

    @PreDestroy
    public void close() throws IOException { lock.release(); lockChannel.close(); }
}
