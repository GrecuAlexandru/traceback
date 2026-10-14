package dev.traceback.rag;

/** One bounded call, no tools. The caller supplies policy separately from untrusted JSON data. */
public interface ChatProvider {
    Result generate(String system,String data);
    record Result(String content,Integer inputTokens,Integer outputTokens) {}
}
