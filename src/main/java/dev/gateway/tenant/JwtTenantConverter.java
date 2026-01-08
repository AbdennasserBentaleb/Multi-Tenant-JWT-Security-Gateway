package dev.gateway.tenant;

import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Custom JWT → {@link AbstractAuthenticationToken} converter.
 *
 * <p>
 * Extracts the {@code tenant_id} claim from the incoming JWT and validates
 * it is a non-null, parseable UUID. The enriched token carries both the
 * standard Spring Security authorities and a tenant-scoped authority
 * {@code TENANT_<uuid>} for optional RBAC policies.
 *
 * <p>
 * The extracted tenant UUID is stored in the token's
 * {@link JwtAuthenticationToken#getDetails()} map so downstream filters
 * ({@link TenantContextFilter}) can retrieve it without re-parsing the JWT.
 *
 * <h2>Claim convention</h2>
 * The OIDC provider (Keycloak) must be configured to include a custom claim:
 * 
 * <pre>{@code
 *   "tenant_id": "a3f1b2c4-..."
 * }</pre>
 * 
 * See the Keycloak client scope "tenant-mapper" in the deployment
 * documentation.
 */
@Component
public class JwtTenantConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    /** JWT claim that carries the tenant identifier. */
    public static final String TENANT_CLAIM = "tenant_id";

    /** Prefix for the tenant-scoped granted authority. */
    private static final String TENANT_AUTHORITY_PREFIX = "TENANT_";

    /**
     * Delegate for extracting standard Spring Security authorities
     * from {@code scope} and {@code roles} claims.
     */
    private final JwtGrantedAuthoritiesConverter defaultConverter;

    public JwtTenantConverter() {
        this.defaultConverter = new JwtGrantedAuthoritiesConverter();
    }

    @Override
    @NonNull
    public AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        UUID tenantId = extractAndValidateTenantId(jwt);

        List<GrantedAuthority> authorities = new ArrayList<>(
                defaultConverter.convert(jwt) != null
                        ? defaultConverter.convert(jwt)
                        : List.of());

        // Add a tenant-scoped authority for optional RBAC policies
        authorities.add(new SimpleGrantedAuthority(TENANT_AUTHORITY_PREFIX + tenantId));

        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, authorities);

        // Store tenant UUID in details for TenantContextFilter retrieval
        token.setDetails(tenantId);

        return token;
    }

    /**
     * Extracts and validates the {@code tenant_id} claim.
     *
     * @param jwt the decoded JWT
     * @return the tenant UUID
     * @throws TenantIdMissingException if the claim is absent
     * @throws TenantIdInvalidException if the claim cannot be parsed as a UUID
     */
    @NonNull
    private UUID extractAndValidateTenantId(@NonNull Jwt jwt) {
        String raw = jwt.getClaimAsString(TENANT_CLAIM);

        if (raw == null || raw.isBlank()) {
            throw new TenantIdMissingException(
                    "JWT is missing the required '" + TENANT_CLAIM + "' claim. " +
                            "Verify the Keycloak token mapper is configured correctly.");
        }

        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new TenantIdInvalidException(
                    "JWT claim '" + TENANT_CLAIM + "' value '" + raw + "' is not a valid UUID.", e);
        }
    }

    // ── Domain exceptions ────────────────────────────────────────────────────

    public static final class TenantIdMissingException extends RuntimeException {
        public TenantIdMissingException(String message) {
            super(message);
        }
    }

    public static final class TenantIdInvalidException extends RuntimeException {
        public TenantIdInvalidException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
