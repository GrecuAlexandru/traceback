package dev.traceback.knowledge;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

@Component
public class DocumentUploadValidator {
    private final int maximumBytes;
    public DocumentUploadValidator(@Value("${traceback.documents.max-file-size-bytes:10485760}") int maximumBytes) {
        if (maximumBytes < 1 || maximumBytes > 52428800) throw new IllegalArgumentException("Document limit must be 1..52428800 bytes");
        this.maximumBytes = maximumBytes;
    }
    public int maximumBytes() { return maximumBytes; }

    public ValidatedUpload validate(MultipartFile file) {
        if (file == null) throw DocumentException.invalid("A file is required.");
        if (file.getSize() > maximumBytes) throw oversized();
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '/' || c == '\\' || c == ':')) {
            throw DocumentException.invalid("Invalid filename; provide a basename only.");
        }
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String type = switch (extension) {
            case "pdf" -> "application/pdf";
            case "md" -> "text/markdown";
            case "txt" -> "text/plain";
            default -> throw DocumentException.invalid("Supported extensions are .pdf, .md and .txt.");
        };
        try {
            MediaType declared = MediaType.parseMediaType(file.getContentType() == null ? "" : file.getContentType());
            if (!type.equals(declared.getType() + "/" + declared.getSubtype())
                    || (declared.getCharset() != null && !StandardCharsets.UTF_8.equals(declared.getCharset()))) {
                throw DocumentException.invalid("Media type must match the supported extension (UTF-8 for text).");
            }
        } catch (IllegalArgumentException ex) {
            throw DocumentException.invalid("Invalid media type.");
        }
        byte[] bytes;
        try (var input = file.getInputStream()) { bytes = input.readNBytes(maximumBytes + 1); }
        catch (IOException ex) { throw DocumentException.invalid("Could not read upload."); }
        if (bytes.length > maximumBytes) throw oversized();
        if (bytes.length == 0) throw DocumentException.invalid("Empty documents are not supported.");
        if (type.equals("application/pdf")) validatePdf(bytes); else validateText(bytes);
        return new ValidatedUpload(name, type, bytes, checksum(bytes));
    }

    private void validateText(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.isBlank() || text.startsWith("%PDF-") || text.codePoints().anyMatch(c ->
                    Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                throw DocumentException.invalid("Content must be nonblank UTF-8 text.");
            }
        } catch (java.nio.charset.CharacterCodingException ex) { throw DocumentException.invalid("Content must be UTF-8 text."); }
    }

    private void validatePdf(byte[] bytes) {
        if (!new String(bytes, 0, Math.min(bytes.length, 8), StandardCharsets.US_ASCII).startsWith("%PDF-")) {
            throw DocumentException.invalid("Invalid PDF content.");
        }
        try (var source = new RandomAccessReadBuffer(bytes)) {
            PDFParser parser = new PDFParser(source);
            try (var document = parser.parse(false)) {
                if (document.isEncrypted() || document.getNumberOfPages() < 1 || document.getNumberOfPages() > 200) {
                    throw DocumentException.invalid("PDF must be unencrypted and contain 1..200 pages.");
                }
                boolean text = false;
                int remaining = 20 * 1024 * 1024;
                for (var page : document.getPages()) {
                    byte[] stream;
                    try (var input = page.getContents()) { stream = input.readNBytes(remaining + 1); }
                    remaining -= stream.length;
                    if (remaining < 0) throw DocumentException.invalid("PDF content streams exceed the validation limit.");
                    PDFStreamParser tokens = new PDFStreamParser(stream);
                    try {
                        Object token;
                        boolean stringOperand = false;
                        while ((token = tokens.parseNextToken()) != null) {
                            if (token instanceof COSString s && s.getBytes().length > 0) stringOperand = true;
                            if (token instanceof COSArray a) {
                                for (var element : a) if (element instanceof COSString s && s.getBytes().length > 0) stringOperand = true;
                            }
                            if (token instanceof Operator op) {
                                if (stringOperand && Set.of("Tj", "TJ", "'", "\"").contains(op.getName())) text = true;
                                stringOperand = false;
                            }
                        }
                    } finally { tokens.close(); }
                }
                if (!text) throw DocumentException.invalid("PDF must contain page text; scanned PDFs are unsupported.");
            }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof DocumentException validation) throw validation;
            throw DocumentException.invalid("Malformed or unsupported PDF.");
        }
    }

    public static String checksum(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private DocumentException oversized() { return new DocumentException(HttpStatus.PAYLOAD_TOO_LARGE, "Document exceeds the configured size limit."); }
    public record ValidatedUpload(String filename, String mediaType, byte[] bytes, String checksum) {}
}
