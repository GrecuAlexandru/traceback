package dev.traceback.organization.dto;

import dev.traceback.organization.MembershipRole;
import dev.traceback.organization.Organization;
import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(UUID id, String name, Instant createdAt, MembershipRole role) {
    public static OrganizationResponse from(Organization organization, MembershipRole role) {
        return new OrganizationResponse(organization.getId(), organization.getName(),
                organization.getCreatedAt(), role);
    }
}
