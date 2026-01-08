package dev.gateway.config;

import dev.gateway.tenant.JwtTenantConverter;
import dev.gateway.tenant.TenantContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security configuration for the JWT Gateway.
 *
 * <h2>Design decisions</h2>
 * <ul>
 * <li><strong>Stateless sessions</strong>: never create an HTTP session —
 * all auth state lives in the JWT (12-Factor VI).</li>
 * <li><strong>OAuth2 Resource Server</strong>: validates JWT signature using
 * the issuer's JWKS endpoint (auto-configured from {@code issuer-uri}).</li>
 * <li><strong>Custom JWT converter</strong>: {@link JwtTenantConverter}
 * extracts
 * {@code tenant_id} before authorities are resolved.</li>
 * <li><strong>TenantContextFilter ordering</strong>: must run AFTER
 * {@link BearerTokenAuthenticationFilter} so the SecurityContext is
 * populated.</li>
 * <li><strong>Method security</strong>: {@code @EnableMethodSecurity} allows
 * {@code @PreAuthorize} annotations in service/controller layers.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

        private final JwtTenantConverter jwtTenantConverter;
        private final TenantContextFilter tenantContextFilter;

        public SecurityConfig(
                        JwtTenantConverter jwtTenantConverter,
                        TenantContextFilter tenantContextFilter) {
                this.jwtTenantConverter = jwtTenantConverter;
                this.tenantContextFilter = tenantContextFilter;
        }

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                http
                                // ── CORS Data ──────────────────────────────────────────────────
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                                // ── Stateless REST API — no sessions, no CSRF ──────────────────
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .csrf(csrf -> csrf.disable())

                                // ── OAuth2 Resource Server ─────────────────────────────────────
                                .oauth2ResourceServer(oauth2 -> oauth2
                                                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtTenantConverter)))

                                // ── Tenant filter AFTER Bearer token filter ────────────────────
                                .addFilterAfter(tenantContextFilter, BearerTokenAuthenticationFilter.class)

                                // ── Endpoint access rules ──────────────────────────────────────
                                .authorizeHttpRequests(auth -> auth
                                                // k3s liveness/readiness probes — no auth
                                                .requestMatchers(
                                                                "/actuator/health",
                                                                "/actuator/health/liveness",
                                                                "/actuator/health/readiness")
                                                .permitAll()
                                                // OpenAPI docs
                                                .requestMatchers(
                                                                "/api-docs/**",
                                                                "/swagger-ui/**",
                                                                "/swagger-ui.html")
                                                .permitAll()
                                                // Frontend Dashboard
                                                .requestMatchers(
                                                                "/",
                                                                "/index.html",
                                                                "/style.css",
                                                                "/app.js")
                                                .permitAll()
                                                // Everything else requires a valid Bearer JWT
                                                .anyRequest().authenticated());

                return http.build();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration configuration = new CorsConfiguration();
                configuration.setAllowedOrigins(List.of("*")); // For portfolio, allow all. Restrict in prod.
                configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", configuration);
                return source;
        }
}
