package dev.traceback.extraction;

/** Offsets are Unicode code points in the normalized extracted artifact. Pages are one-based. */
public record SourceBlock(String text, String section, Integer page, int start, int end) {}
