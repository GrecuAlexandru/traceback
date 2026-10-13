package dev.traceback.incident;

import dev.traceback.incident.dto.CreateIncidentRequest;
import dev.traceback.incident.dto.UpdateIncidentRequest;
import dev.traceback.incident.dto.IncidentResponse;
import dev.traceback.incident.dto.IncidentPageResponse;
import dev.traceback.organization.OrganizationAuthorizationService;
import dev.traceback.organization.OrganizationPermissionDeniedException;
import dev.traceback.security.HumanIdentityPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

@Service
@Validated
@Transactional(readOnly = true)
public class IncidentService {
    private final IncidentRepository repository;
    private final OrganizationAuthorizationService authorization;

    public IncidentService(IncidentRepository repository, OrganizationAuthorizationService authorization) {
        this.repository = repository;
        this.authorization = authorization;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IncidentResponse createIncident(Jwt jwt, UUID organizationId, @NotNull @Valid CreateIncidentRequest request) {
        HumanIdentityPolicy.Identity identity = authorization.requireAccess(jwt, organizationId, true);
        UUID id = UUID.randomUUID();
        if (repository.insertAuthorized(organizationId, id, request.title(), request.description(),
                identity.issuer(), identity.subject()) != 1) {
            throw new OrganizationPermissionDeniedException();
        }
        return IncidentResponse.fromEntity(find(organizationId, id, identity));
    }

    public IncidentPageResponse getIncidents(Jwt jwt, UUID organizationId, IncidentStatus status,
                                             @PositiveOrZero int page, @Min(1) @Max(100) int size) {
        HumanIdentityPolicy.Identity identity = authorization.requireAccess(jwt, organizationId, false);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Incident> result = status == null
                ? repository.findAuthorized(organizationId, identity.issuer(), identity.subject(), pageable)
                : repository.findAuthorizedByStatus(organizationId, status, identity.issuer(), identity.subject(), pageable);
        return IncidentPageResponse.fromPage(result);
    }

    public IncidentResponse getIncident(Jwt jwt, UUID organizationId, UUID id) {
        HumanIdentityPolicy.Identity identity = authorization.requireAccess(jwt, organizationId, false);
        return IncidentResponse.fromEntity(find(organizationId, id, identity));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IncidentResponse updateIncident(Jwt jwt, UUID organizationId, UUID id,
                                            @NotNull @Valid UpdateIncidentRequest request) {
        HumanIdentityPolicy.Identity identity = authorization.requireAccess(jwt, organizationId, true);
        Incident incident = find(organizationId, id, identity);
        if (incident.getVersion() != request.version()) throw new IncidentVersionConflictException();
        String title = request.title() == null ? incident.getTitle() : request.title();
        String description = request.description() == null ? incident.getDescription() : request.description();
        IncidentStatus status = request.status() == null ? incident.getStatus() : request.status();
        // Preserve the previous JPA dirty-checking behavior for a no-op PATCH.
        if (title.equals(incident.getTitle()) && description.equals(incident.getDescription())
                && status == incident.getStatus()) {
            return IncidentResponse.fromEntity(incident);
        }
        int updated = repository.updateAuthorized(organizationId, id, request.version(), title, description, status.name(),
                identity.issuer(), identity.subject());
        if (updated != 1) {
            // A role revoked between authorization and the SQL mutation takes precedence
            // over a version conflict. Never retry an update with a newer version.
            authorization.requireAccess(jwt, organizationId, true);
            throw new IncidentVersionConflictException();
        }
        return IncidentResponse.fromEntity(find(organizationId, id, identity));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteIncident(Jwt jwt, UUID organizationId, UUID id, @PositiveOrZero long version) {
        HumanIdentityPolicy.Identity identity = authorization.requireAccess(jwt, organizationId, true);
        Incident incident = find(organizationId, id, identity);
        if (incident.getVersion() != version) throw new IncidentVersionConflictException();
        if (repository.deleteAuthorized(organizationId, id, version, identity.issuer(), identity.subject()) != 1) {
            authorization.requireAccess(jwt, organizationId, true);
            throw new IncidentVersionConflictException();
        }
    }

    private Incident find(UUID organizationId, UUID id, HumanIdentityPolicy.Identity identity) {
        return repository.findAuthorizedById(organizationId, id, identity.issuer(), identity.subject())
                .orElseThrow(IncidentNotFoundException::new);
    }
}
