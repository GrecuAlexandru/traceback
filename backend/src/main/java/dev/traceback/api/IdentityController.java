package dev.traceback.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
public class IdentityController {
    @GetMapping("/api/v1/me")
    public IdentityResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new IdentityResponse(
                jwt.getSubject(),
                jwt.getClaimAsString("preferred_username"),
                Objects.toString(jwt.getIssuer(), null)
        );
    }

    public record IdentityResponse(
            String subject,
            String username,
            String issuer
    ) {}
}
