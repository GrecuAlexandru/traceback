package dev.traceback.incident.dto;

import dev.traceback.incident.Incident;
import org.springframework.data.domain.Page;

import java.util.List;

public record IncidentPageResponse(
        List<IncidentResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public static IncidentPageResponse fromPage(Page<Incident> result) {
        List<IncidentResponse> items = result.getContent()
                .stream()
                .map(IncidentResponse::fromEntity)
                .toList();

        return new IncidentPageResponse(
                items,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext()
        );
    }
}
