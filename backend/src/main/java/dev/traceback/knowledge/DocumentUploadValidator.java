package dev.traceback.knowledge;

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

@Component
public class DocumentUploadValidator {
    private final int maximumBytes;
    private final dev.traceback.extraction.DocumentExtractor extractor;
    public DocumentUploadValidator(dev.traceback.extraction.DocumentExtractor extractor, @Value("${traceback.documents.max-file-size-bytes:10485760}") int maximumBytes) {
        if (maximumBytes < 1 || maximumBytes > 52428800) throw new IllegalArgumentException("Document limit must be 1..52428800 bytes");
        this.maximumBytes = maximumBytes; this.extractor = extractor;
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
        try { extractor.extract(bytes, "application/pdf"); }
        catch (dev.traceback.extraction.ExtractionFailure ex) {
            if(ex.code().equals("FILE_LIMIT"))throw oversized();
            if (java.util.Set.of("PARSER_UNAVAILABLE", "PARSER_BUSY", "EXTRACTION_INTERRUPTED").contains(ex.code()))
                throw DocumentException.storage();
            throw DocumentException.invalid("Malformed, unextractable or limit-exceeding PDF.");
        }
    }

    public static String checksum(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private DocumentException oversized() { return new DocumentException(HttpStatus.PAYLOAD_TOO_LARGE, "Document exceeds the configured size limit."); }
    public record ValidatedUpload(String filename, String mediaType, byte[] bytes, String checksum) {}
}
