package dev.traceback.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import java.time.Instant;
import java.util.List;

/** Keep issuer discovery/audience configuration, and require an expiring REST access token. */
@Configuration
@EnableConfigurationProperties(OAuth2ResourceServerProperties.class)
public class RestJwtConfiguration {
    @Bean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
        var configuration=properties.getJwt();String issuer=configuration.getIssuerUri();
        List<String> audiences=List.copyOf(configuration.getAudiences());
        if(issuer==null || issuer.isBlank() || audiences.isEmpty())throw new IllegalArgumentException("REST issuer and audience required");
        // Preserve lazy discovery so optional/local issuer downtime does not prevent startup.
        return new SupplierJwtDecoder(()->{
            var decoder=NimbusJwtDecoder.withIssuerLocation(issuer).jwsAlgorithms(algorithms->{
                algorithms.clear();
                for(String name:configuration.getJwsAlgorithms()) {
                    var algorithm=SignatureAlgorithm.from(name);
                    if(algorithm==null)throw new IllegalArgumentException("Unsupported JWT signature algorithm");
                    algorithms.add(algorithm);
                }
            }).build();
            OAuth2TokenValidator<Jwt> access=jwt->jwt.getExpiresAt()!=null && jwt.getExpiresAt().isAfter(Instant.now())
                    && jwt.getAudience().stream().anyMatch(audiences::contains)
                    ?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Invalid REST access token",null));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),access));
            return decoder;
        });
    }
}
