package dev.traceback.organization;

import dev.traceback.security.HumanIdentityPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
public class OrganizationAuthorizationService {
    private final OrganizationMembershipRepository memberships;
    private final HumanIdentityPolicy identityPolicy;

    public OrganizationAuthorizationService(OrganizationMembershipRepository memberships,
                                            HumanIdentityPolicy identityPolicy) {
        this.memberships = memberships;
        this.identityPolicy = identityPolicy;
    }

    public HumanIdentityPolicy.Identity requireAccess(Jwt jwt, UUID organizationId, boolean write) {
        HumanIdentityPolicy.Identity identity = identityPolicy.requireHuman(jwt);
        MembershipRole role = memberships.findRoleForIdentity(organizationId, identity.issuer(), identity.subject())
                .orElseThrow(OrganizationPermissionDeniedException::new);
        if (write && role != MembershipRole.ADMIN && role != MembershipRole.ENGINEER) {
            throw new OrganizationPermissionDeniedException();
        }
        return identity;
    }
}
