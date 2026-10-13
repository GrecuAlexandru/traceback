package dev.traceback.knowledge.dto;

import java.util.List;

public record DocumentPageResponse<T>(List<T> content, int page, int size, long totalElements, long totalPages) {
    public static <T> DocumentPageResponse<T> of(List<T> content, int page, int size, long total) {
        return new DocumentPageResponse<>(content, page, size, total, (total + size - 1) / size);
    }
}
