package dev.traceback.indexing;
public class EmbeddingFailure extends RuntimeException {
    private final String code;
    public EmbeddingFailure(String code) {super(code);this.code=code;}
    public String code() {return code;}
}
