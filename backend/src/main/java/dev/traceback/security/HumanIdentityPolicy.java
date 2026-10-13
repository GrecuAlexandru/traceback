package dev.traceback.security;

import dev.traceback.organization.OrganizationAccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class HumanIdentityPolicy {
    public Identity requireHuman(Jwt jwt) {
        // This claim must be assigned by the trusted issuer, never by a user-editable
        // profile field or a mapper shared with clients that enable service accounts.
        if (jwt == null || !"human".equals(jwt.getClaims().get("traceback_principal_type"))) {
            throw new OrganizationAccessDeniedException();
        }
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        String subject = jwt.getSubject();
        if (issuer == null || issuer.isBlank() || issuer.length() > 255
                || subject == null || subject.isBlank() || subject.length() > 255) {
            throw new OrganizationAccessDeniedException();
        }
        return new Identity(issuer, subject);
    }

    public record Identity(String issuer, String subject) {}
}
