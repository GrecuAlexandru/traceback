package dev.traceback.knowledge;

import org.springframework.http.HttpStatus;

public class DocumentException extends RuntimeException {
    private final HttpStatus status;
    public DocumentException(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
    public static DocumentException missing() { return new DocumentException(HttpStatus.NOT_FOUND, "Document or revision not found."); }
    public static DocumentException conflict() { return new DocumentException(HttpStatus.CONFLICT, "Document changed or has an upload in progress. Reload and retry."); }
    public static DocumentException storage() { return new DocumentException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage is unavailable."); }
    public static DocumentException invalid(String detail) { return new DocumentException(HttpStatus.BAD_REQUEST, detail); }
}
