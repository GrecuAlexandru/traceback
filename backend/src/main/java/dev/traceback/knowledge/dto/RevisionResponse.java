package dev.traceback.knowledge.dto;

import java.time.Instant;
import java.util.UUID;

public record RevisionResponse(UUID id, UUID organizationId, UUID documentId, int revisionNumber,
                               String originalFilename, String mediaType, long fileSize, String checksum,
                               UUID uploadedBy, String status, Instant createdAt) {}
