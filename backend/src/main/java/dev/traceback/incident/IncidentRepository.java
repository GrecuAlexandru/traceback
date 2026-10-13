package dev.traceback.incident;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

// Deliberately expose no global findAll/findById/save/delete methods. Each query
// requires an organization and authenticated identity, including pagination counts.
public interface IncidentRepository extends Repository<Incident, UUID> {
    String READ_SCOPE = """
            i.organizationId = :organizationId AND EXISTS (
                SELECT m.id FROM OrganizationMembership m
                WHERE m.organization.id = i.organizationId
                  AND m.user.issuer = :issuer AND m.user.subject = :subject)
            """;

    String WRITE_MEMBERSHIP = """
            EXISTS (SELECT 1 FROM organization_memberships m
                JOIN app_users u ON u.id = m.user_id
                WHERE m.organization_id = :organizationId
                  AND u.issuer = :issuer AND u.subject = :subject
                  AND m.role IN ('ADMIN', 'ENGINEER'))
            """;

    @Query("SELECT i FROM Incident i WHERE i.id = :id AND " + READ_SCOPE)
    Optional<Incident> findAuthorizedById(@Param("organizationId") UUID organizationId, @Param("id") UUID id,
                                        @Param("issuer") String issuer, @Param("subject") String subject);

    @Query(value = "SELECT i FROM Incident i WHERE " + READ_SCOPE,
           countQuery = "SELECT count(i) FROM Incident i WHERE " + READ_SCOPE)
    Page<Incident> findAuthorized(@Param("organizationId") UUID organizationId, @Param("issuer") String issuer,
                                 @Param("subject") String subject, Pageable pageable);

    @Query(value = "SELECT i FROM Incident i WHERE i.status = :status AND " + READ_SCOPE,
           countQuery = "SELECT count(i) FROM Incident i WHERE i.status = :status AND " + READ_SCOPE)
    Page<Incident> findAuthorizedByStatus(@Param("organizationId") UUID organizationId,
                                         @Param("status") IncidentStatus status, @Param("issuer") String issuer,
                                         @Param("subject") String subject, Pageable pageable);

    @Modifying
    @Query(value = """
            INSERT INTO incidents (id, organization_id, title, description)
            SELECT :id, :organizationId, :title, :description WHERE
            """ + WRITE_MEMBERSHIP, nativeQuery = true)
    int insertAuthorized(@Param("organizationId") UUID organizationId, @Param("id") UUID id,
                         @Param("title") String title, @Param("description") String description,
                         @Param("issuer") String issuer, @Param("subject") String subject);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE incidents SET title = :title, description = :description, status = :status, version = version + 1
            WHERE organization_id = :organizationId AND id = :id AND version = :version AND
            """ + WRITE_MEMBERSHIP, nativeQuery = true)
    int updateAuthorized(@Param("organizationId") UUID organizationId, @Param("id") UUID id,
                         @Param("version") long version, @Param("title") String title,
                         @Param("description") String description, @Param("status") String status,
                         @Param("issuer") String issuer, @Param("subject") String subject);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            DELETE FROM incidents WHERE organization_id = :organizationId AND id = :id AND version = :version AND
            """ + WRITE_MEMBERSHIP, nativeQuery = true)
    int deleteAuthorized(@Param("organizationId") UUID organizationId, @Param("id") UUID id,
                         @Param("version") long version, @Param("issuer") String issuer, @Param("subject") String subject);
}
