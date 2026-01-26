package dev.gateway.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD unit tests for {@link JwtTenantConverter}.
 *
 * <p>
 * Tests the happy path, missing claim, and malformed claim scenarios
 * without involving Spring Security context.
 */
@DisplayName("JwtTenantConverter")
class JwtTenantConverterTest {

    private JwtTenantConverter converter;

    @BeforeEach
    void setUp() {
        converter = new JwtTenantConverter();
    }

    private Jwt buildJwt(Map<String, Object> extraClaims) {
        Map<String, Object> headers = Map.of("alg", "RS256");
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", "user-123");
        claims.put("scope", "openid profile");
        claims.putAll(extraClaims);

        return Jwt.withTokenValue("mock-token")
                .headers(h -> h.putAll(headers))
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("extracts tenant_id UUID from JWT claim correctly")
        void convert_withValidTenantId_extractsUuid() {
            UUID tenantId = UUID.randomUUID();
            Jwt jwt = buildJwt(Map.of(JwtTenantConverter.TENANT_CLAIM, tenantId.toString()));

            AbstractAuthenticationToken token = converter.convert(jwt);

            assertThat(token).isNotNull();
            assertThat(token.getDetails()).isInstanceOf(UUID.class);
            assertThat((UUID) token.getDetails()).isEqualTo(tenantId);
        }

        @Test
        @DisplayName("adds a TENANT_<uuid> GrantedAuthority to the token")
        void convert_addsTenantAuthority() {
            UUID tenantId = UUID.randomUUID();
            Jwt jwt = buildJwt(Map.of(JwtTenantConverter.TENANT_CLAIM, tenantId.toString()));

            AbstractAuthenticationToken token = converter.convert(jwt);

            boolean hasTenantAuthority = token.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("TENANT_" + tenantId));
            assertThat(hasTenantAuthority).isTrue();
        }

        @Test
        @DisplayName("trims whitespace from tenant_id claim before parsing")
        void convert_trimsTenantIdWhitespace() {
            UUID tenantId = UUID.randomUUID();
            Jwt jwt = buildJwt(Map.of(
                    JwtTenantConverter.TENANT_CLAIM, "  " + tenantId + "  "));

            AbstractAuthenticationToken token = converter.convert(jwt);

            assertThat((UUID) token.getDetails()).isEqualTo(tenantId);
        }
    }

    @Nested
    @DisplayName("Missing tenant_id claim")
    class MissingClaim {

        @Test
        @DisplayName("throws TenantIdMissingException when claim is absent")
        void convert_withoutTenantClaim_throwsMissingException() {
            Jwt jwt = buildJwt(Map.of()); // no tenant_id

            assertThatThrownBy(() -> converter.convert(jwt))
                    .isInstanceOf(JwtTenantConverter.TenantIdMissingException.class)
                    .hasMessageContaining(JwtTenantConverter.TENANT_CLAIM);
        }

        @Test
        @DisplayName("throws TenantIdMissingException when claim is blank")
        void convert_withBlankTenantClaim_throwsMissingException() {
            Jwt jwt = buildJwt(Map.of(JwtTenantConverter.TENANT_CLAIM, "   "));

            assertThatThrownBy(() -> converter.convert(jwt))
                    .isInstanceOf(JwtTenantConverter.TenantIdMissingException.class);
        }
    }

    @Nested
    @DisplayName("Invalid tenant_id claim")
    class InvalidClaim {

        @Test
        @DisplayName("throws TenantIdInvalidException when claim is not a UUID")
        void convert_withNonUuidClaim_throwsInvalidException() {
            Jwt jwt = buildJwt(Map.of(JwtTenantConverter.TENANT_CLAIM, "not-a-uuid"));

            assertThatThrownBy(() -> converter.convert(jwt))
                    .isInstanceOf(JwtTenantConverter.TenantIdInvalidException.class)
                    .hasMessageContaining("not-a-uuid");
        }
    }
}
