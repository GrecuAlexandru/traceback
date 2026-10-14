
package dev.traceback.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, @org.springframework.beans.factory.annotation.Value("${traceback.runtime:api}") String runtime) throws Exception {
        if (runtime.equals("worker")) {
            return http.csrf(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                    .formLogin(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                            .anyRequest().denyAll()).build();
        }

        return http
                .csrf(AbstractHttpConfigurer::disable)

                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/ping",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/error"
                        ).permitAll()

                        // Retired global routes cannot become a fallback or redirect bypass.
                        .requestMatchers("/api/v1/incidents", "/api/v1/incidents/**").denyAll()
                        .anyRequest().authenticated()
                )

                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(Customizer.withDefaults())
                )

                .build();
    }
}
