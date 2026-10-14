package dev.traceback.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Component
public class DocumentExtractor {
    private final String image;
    private final int maxPages, maxText, timeout;
    private final int maxBytes;
    private final Semaphore slots = new Semaphore(1);
    @org.springframework.beans.factory.annotation.Autowired
    public DocumentExtractor(@Value("${traceback.extraction.image:traceback-pdf:m12}") String image,
                             @Value("${traceback.extraction.max-pages:200}") int maxPages,
                             @Value("${traceback.extraction.max-text:1000000}") int maxText,
                             @Value("${traceback.extraction.timeout-seconds:30}") int timeout,
                             @Value("${traceback.extraction.max-file-bytes:10485760}") int maxBytes) {
        if (!image.matches("[a-zA-Z0-9./:_@-]{1,200}") || maxPages < 1 || maxPages > 200 || maxText < 1
                || maxText > 1000000 || timeout < 1 || timeout > 60 || maxBytes<1 || maxBytes>10485760) throw new IllegalArgumentException("Invalid extraction limits");
        this.image=image; this.maxPages=maxPages; this.maxText=maxText; this.timeout=timeout;this.maxBytes=maxBytes;
    }
    public DocumentExtractor(String image,int maxPages,int maxText,int timeout){this(image,maxPages,maxText,timeout,10485760);}
    public int maximumBytes(){return maxBytes;}
    public List<SourceBlock> extract(byte[] bytes, String media) {
        if (bytes.length > maxBytes) throw new ExtractionFailure("FILE_LIMIT");
        return switch (media) {
            case "text/plain", "text/markdown" -> TextExtraction.extract(bytes, media.equals("text/markdown"), maxText);
            case "application/pdf" -> pdf(bytes);
            default -> throw new ExtractionFailure("UNSUPPORTED_FORMAT");
        };
    }
    private List<SourceBlock> pdf(byte[] bytes) {
        if (!slots.tryAcquire()) throw new ExtractionFailure("PARSER_BUSY");
        String name = "traceback-pdf-" + UUID.randomUUID();
        Process process = null;
        try (var io = Executors.newVirtualThreadPerTaskExecutor()) {
            process = new ProcessBuilder("docker", "run", "--rm", "--pull=never", "--name", name, "-i",
                    "--network=none", "--read-only", "--memory=256m", "--memory-swap=256m", "--cpus=1",
                    "--pids-limit=64", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user=65534:65534",
                    "--tmpfs=/tmp:rw,noexec,nosuid,size=32m", image, String.valueOf(maxBytes), String.valueOf(maxPages), String.valueOf(maxText))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process child = process;
            Future<?> input = io.submit(() -> { try (var out = child.getOutputStream()) { out.write(bytes); } catch (IOException ex) { throw new UncheckedIOException(ex); } });
            Future<byte[]> output = io.submit(() -> {
                try (var in = child.getInputStream()) {
                    int cap = maxText * 8 + 1048576;
                    byte[] result = in.readNBytes(cap + 1);
                    if (result.length > cap) throw new ExtractionFailure("TEXT_LIMIT");
                    return result;
                }
            });
            long deadline = System.nanoTime() + Duration.ofSeconds(timeout).toNanos();
            try {
                input.get(timeout, TimeUnit.SECONDS);
                byte[] result = output.get(Math.max(1, deadline-System.nanoTime()), TimeUnit.NANOSECONDS);
                if (!child.waitFor(Math.max(1, deadline-System.nanoTime()), TimeUnit.NANOSECONDS)) throw new TimeoutException();
                if (child.exitValue() != 0) throw new ExtractionFailure(switch (child.exitValue()) {
                    case 10 -> "NO_TEXT"; case 11 -> "PAGE_LIMIT"; case 12 -> "TEXT_LIMIT"; case 13 -> "FILE_LIMIT";
                    case 14 -> "ENCRYPTED_PDF"; case 16,125,126,127 -> "PARSER_UNAVAILABLE"; case 17 -> "STRUCTURE_LIMIT";
                    case 137 -> "EXTRACTION_RESOURCE_LIMIT"; default -> "INVALID_PDF";
                });
                return decode(result);
            } catch (TimeoutException ex) { throw new ExtractionFailure("EXTRACTION_TIMEOUT"); }
            finally {
                child.destroyForcibly();
                remove(name);
                input.cancel(true); output.cancel(true);
            }
        } catch (ExtractionFailure ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new ExtractionFailure("EXTRACTION_INTERRUPTED"); }
        catch (ExecutionException ex) {
            if(ex.getCause() instanceof ExtractionFailure failure)throw failure;
            throw new ExtractionFailure("PARSER_UNAVAILABLE");
        }
        catch (IOException ex) { throw new ExtractionFailure("PARSER_UNAVAILABLE"); }
        finally { if (process != null) process.destroyForcibly(); slots.release(); }
    }
    private void remove(String name) {
        try {
            var cleanup = new ProcessBuilder("docker", "rm", "-f", name).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!cleanup.waitFor(5, TimeUnit.SECONDS)) cleanup.destroyForcibly();
        } catch (IOException ex) { /* Daemon outage: operational orphan cleanup is documented. */ }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
    private List<SourceBlock> decode(byte[] bytes) throws IOException {
        var input = new DataInputStream(new ByteArrayInputStream(bytes));
        if (input.readInt() != 0x54524231) throw new ExtractionFailure("INVALID_PDF");
        int count = input.readInt(), total = 0;
        if (count < 1 || count > 8192) throw new ExtractionFailure("STRUCTURE_LIMIT");
        var result = new ArrayList<SourceBlock>();
        for (int i=0;i<count;i++) {
            int page=input.readInt(), start=input.readInt(), end=input.readInt(), size=input.readInt();
            if (page<1 || page>maxPages || start<0 || end<start || size<1 || size>input.available()) throw new ExtractionFailure("INVALID_PDF");
            String text = new String(input.readNBytes(size), StandardCharsets.UTF_8);
            total += text.codePointCount(0,text.length());
            if (total>maxText) throw new ExtractionFailure("TEXT_LIMIT");
            result.add(new SourceBlock(text,null,page,start,end));
        }
        if (input.available()!=0) throw new ExtractionFailure("INVALID_PDF");
        return List.copyOf(result);
    }
}
