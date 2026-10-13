package dev.traceback.knowledge.dto;

import dev.traceback.knowledge.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(UUID id, UUID organizationId, String title, UUID createdBy,
                               DocumentStatus status, long version, Instant createdAt, Instant modifiedAt,
                               RevisionResponse currentRevision) {}
