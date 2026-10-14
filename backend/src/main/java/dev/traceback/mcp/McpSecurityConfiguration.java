package dev.traceback.mcp;

import dev.traceback.security.HumanIdentityPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class McpSecurityConfiguration {
    @Bean @Order(1)
    SecurityFilterChain mcpSecurity(HttpSecurity http, McpSettings settings, HumanIdentityPolicy humans,
            io.micrometer.core.instrument.MeterRegistry metrics,
            @Value("${traceback.runtime:api}") String runtime,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) throws Exception {
        var boundary=new McpBoundaryFilter(settings,metrics,runtime.equals("api"),issuer);
        http.securityMatcher(request -> settings.matches(request.getRequestURI()))
                .csrf(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(boundary, org.springframework.security.web.header.HeaderWriterFilter.class)
                .addFilterAfter(new McpProtocolFilter(settings,metrics), org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a.requestMatchers(settings.metadataPath()).permitAll()
                        .anyRequest().access((authentication, context) -> {
                            var auth=authentication.get();
                            boolean allowed=false;
                            if (auth instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
                                try { humans.requireHuman(token.getToken());allowed=true; }
                                catch (dev.traceback.organization.OrganizationAccessDeniedException ignored) { }
                            }
                            if (!allowed) metrics.counter("traceback.mcp.http.denials","reason","human").increment();
                            return new org.springframework.security.authorization.AuthorizationDecision(allowed);
                        }))
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.decoder(decoder(issuer,settings.audience())))
                        .authenticationEntryPoint((request,response,error) -> {
                            metrics.counter("traceback.mcp.http.denials","reason","authentication").increment();
                            response.setHeader("WWW-Authenticate", "Bearer resource_metadata=\""+settings.metadataUrl()+"\"");
                            response.setStatus(401);
                        }))
                .exceptionHandling(e -> e.accessDeniedHandler((request,response,error) -> response.setStatus(403)));
        return http.build();
    }
    static JwtDecoder decoder(String issuer,String audience) {
        // Keycloak's JWK URL is issuer-owned configuration, never a caller-provided URL.
        NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(issuer+"/protocol/openid-connect/certs").build();
        OAuth2TokenValidator<Jwt> target=jwt -> jwt.getAudience().contains(audience) && jwt.getExpiresAt()!=null
                && jwt.getExpiresAt().isAfter(java.time.Instant.now())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Invalid MCP token",null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),target));
        return decoder;
    }
}
