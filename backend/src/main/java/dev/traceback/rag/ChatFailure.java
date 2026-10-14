package dev.traceback.rag;

import org.springframework.http.HttpStatus;

/** Only fixed application codes may enter public errors or operational metrics. */
public final class ChatFailure extends RuntimeException {
    private final HttpStatus status;
    public ChatFailure(String code, HttpStatus status) { super(code); this.status = status; }
    public String code() { return getMessage(); }
    public HttpStatus status() { return status; }
    public static ChatFailure missing() { return new ChatFailure("CONVERSATION_NOT_FOUND", HttpStatus.NOT_FOUND); }
    public static ChatFailure conflict(String code) { return new ChatFailure(code, HttpStatus.CONFLICT); }
    public static ChatFailure provider(String code) { return new ChatFailure(code, HttpStatus.SERVICE_UNAVAILABLE); }
}
