package dev.traceback.organization;

import dev.traceback.organization.dto.CreateOrganizationRequest;
import dev.traceback.organization.dto.OrganizationResponse;
import dev.traceback.security.HumanIdentityPolicy;
import dev.traceback.user.AppUser;
import dev.traceback.user.AppUserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

@Service
@Validated
@Transactional(readOnly = true)
public class OrganizationService {
    private final AppUserRepository users;
    private final OrganizationRepository organizations;
    private final OrganizationMembershipRepository memberships;
    private final HumanIdentityPolicy identityPolicy;

    public OrganizationService(AppUserRepository users, OrganizationRepository organizations,
                               OrganizationMembershipRepository memberships, HumanIdentityPolicy identityPolicy) {
        this.users = users;
        this.organizations = organizations;
        this.memberships = memberships;
        this.identityPolicy = identityPolicy;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrganizationResponse create(Jwt jwt, @NotNull @Valid CreateOrganizationRequest request) {
        HumanIdentityPolicy.Identity identity = identityPolicy.requireHuman(jwt);
        users.insertIfAbsent(UUID.randomUUID(), identity.issuer(), identity.subject());
        AppUser user = users.findByIssuerAndSubject(identity.issuer(), identity.subject()).orElseThrow();
        Organization organization = organizations.save(new Organization(request.name().strip()));
        memberships.saveAndFlush(new OrganizationMembership(organization, user, MembershipRole.ADMIN));
        return OrganizationResponse.from(organization, MembershipRole.ADMIN);
    }

    public List<OrganizationResponse> listMine(Jwt jwt, @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(101) int limit) {
        var identity = identityPolicy.requireHuman(jwt);
        return memberships.findOrganizationsForIdentity(identity.issuer(), identity.subject(),
                org.springframework.data.domain.PageRequest.of(0, limit));
    }

    public List<OrganizationResponse> listMine(Jwt jwt) {
        HumanIdentityPolicy.Identity identity = identityPolicy.requireHuman(jwt);
        return memberships.findOrganizationsForIdentity(identity.issuer(), identity.subject());
    }
}
