package dev.traceback.incident.dto;

import dev.traceback.incident.Incident;
import dev.traceback.incident.IncidentStatus;

import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
    UUID id,
    String title,
    String description,
    IncidentStatus status,
    long version,
    Instant createdAt
) {

    public static IncidentResponse fromEntity(Incident incident) {
        return new IncidentResponse(
                incident.getId(),
                incident.getTitle(),
                incident.getDescription(),
                incident.getStatus(),
                incident.getVersion(),
                incident.getCreatedAt()
        );
    }
}
