package dev.traceback.extraction;

public class ExtractionFailure extends RuntimeException {
    private final String code;
    public ExtractionFailure(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
