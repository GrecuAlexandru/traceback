package dev.traceback.extraction;

import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.*;

/** Standalone entry point: never loads Spring, credentials, storage or network clients. */
public final class PdfProcess {
    private PdfProcess() {}
    public static void main(String[] args) {
        try {
            isolation();
            int maxBytes = Integer.parseInt(args[0]), maxPages = Integer.parseInt(args[1]), maxText = Integer.parseInt(args[2]);
            byte[] bytes = System.in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new ExtractionFailure("FILE_LIMIT");
            var output = new DataOutputStream(System.out);
            try (var source = new RandomAccessReadBuffer(bytes); var document = new PDFParser(source).parse(false)) {
                if (document.isEncrypted()) throw new ExtractionFailure("ENCRYPTED_PDF");
                if (document.getNumberOfPages() < 1 || document.getNumberOfPages() > maxPages) throw new ExtractionFailure("PAGE_LIMIT");
                var blocks = new ArrayList<SourceBlock>();
                int offset = 0;
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    int remaining = maxText - offset;
                    var writer = new Writer() {
                        final StringBuilder value = new StringBuilder();
                        @Override public void write(char[] b, int off, int len) throws IOException {
                            if ((long)value.length() + len > (long)remaining * 2) throw new ExtractionFailure("TEXT_LIMIT");
                            value.append(b, off, len);
                        }
                        @Override public void flush() {}
                        @Override public void close() {}
                        @Override public String toString() { return value.toString(); }
                    };
                    var stripper = new PDFTextStripper();
                    stripper.setStartPage(page); stripper.setEndPage(page); stripper.setSortByPosition(true);
                    stripper.writeText(document, writer);
                    if (!writer.toString().isBlank()) {
                        var pageBlocks = TextExtraction.blocks(writer.toString(), false, page, remaining);
                        for (var b : pageBlocks) {
                            if(blocks.size()>=8192)throw new ExtractionFailure("STRUCTURE_LIMIT");
                            blocks.add(new SourceBlock(b.text(), null, page, offset+b.start(), offset+b.end()));
                        }
                    }
                    offset += writer.toString().codePointCount(0, writer.toString().length()) + 1;
                    if (offset > maxText) throw new ExtractionFailure("TEXT_LIMIT");
                }
                if (blocks.isEmpty()) throw new ExtractionFailure("NO_TEXT");
                output.writeInt(0x54524231); output.writeInt(blocks.size());
                for (var b : blocks) {
                    byte[] text = b.text().getBytes(StandardCharsets.UTF_8);
                    output.writeInt(b.page()); output.writeInt(b.start()); output.writeInt(b.end()); output.writeInt(text.length); output.write(text);
                }
                output.flush();
            }
        } catch (Throwable ex) {
            // Fixed exit codes only. Malicious/parser diagnostics never leave the container.
            System.exit(ex instanceof ExtractionFailure f ? switch (f.code()) {
                case "NO_TEXT" -> 10; case "PAGE_LIMIT" -> 11; case "TEXT_LIMIT" -> 12; case "FILE_LIMIT" -> 13;
                case "ENCRYPTED_PDF" -> 14; case "ISOLATION_UNAVAILABLE" -> 16; case "STRUCTURE_LIMIT" -> 17; default -> 15;
            } : 15);
        }
    }
    private static void isolation() {
        try {
            // Linux cgroup v2 is the supported local runtime. Refuse unenforced limits.
            long memory=Long.parseLong(Files.readString(Path.of("/sys/fs/cgroup/memory.max")).strip());
            long swap=Long.parseLong(Files.readString(Path.of("/sys/fs/cgroup/memory.swap.max")).strip());
            long pids=Long.parseLong(Files.readString(Path.of("/sys/fs/cgroup/pids.max")).strip());
            String[] cpu=Files.readString(Path.of("/sys/fs/cgroup/cpu.max")).strip().split("\\s+");
            String status=Files.readString(Path.of("/proc/self/status"));
            if(memory>268435456 || swap!=0 || pids>64 || Long.parseLong(cpu[0])>Long.parseLong(cpu[1])
                    || !status.matches("(?s).*NoNewPrivs:\\s+1\\n.*") || !status.matches("(?s).*CapEff:\\s+0+\\n.*"))
                throw new ExtractionFailure("ISOLATION_UNAVAILABLE");
        }catch(IOException | NumberFormatException ex){throw new ExtractionFailure("ISOLATION_UNAVAILABLE");}
    }
}
