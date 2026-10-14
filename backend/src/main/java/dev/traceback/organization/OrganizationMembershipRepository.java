package dev.traceback.organization;

import dev.traceback.organization.dto.OrganizationResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, UUID> {
    @Query("""
            SELECT m.role FROM OrganizationMembership m
            WHERE m.organization.id = :organizationId
              AND m.user.issuer = :issuer AND m.user.subject = :subject
            """)
    Optional<MembershipRole> findRoleForIdentity(@Param("organizationId") UUID organizationId,
                                               @Param("issuer") String issuer, @Param("subject") String subject);

    @Query("""
            SELECT new dev.traceback.organization.dto.OrganizationResponse(
                o.id, o.name, o.createdAt, m.role)
            FROM OrganizationMembership m
            JOIN m.organization o
            JOIN m.user u
            WHERE u.issuer = :issuer AND u.subject = :subject
            ORDER BY o.createdAt DESC, o.id DESC
            """)
    List<OrganizationResponse> findOrganizationsForIdentity(@Param("issuer") String issuer,
            @Param("subject") String subject, org.springframework.data.domain.Pageable pageable);

    default List<OrganizationResponse> findOrganizationsForIdentity(String issuer, String subject) {
        return findOrganizationsForIdentity(issuer, subject, org.springframework.data.domain.Pageable.unpaged());
    }
}
