package dev.gateway.tenant;

import org.springframework.lang.NonNull;

import java.util.UUID;

/**
 * Holds the current request's {@code tenant_id} using Java 25 {@link ScopedValue}.
 */
public final class TenantContext {

    public static final ScopedValue<UUID> CURRENT_TENANT = ScopedValue.newInstance();

    private TenantContext() {
        // Utility class — prevent instantiation
    }

    /**
     * Returns the current tenant identifier.
     *
     * @return the tenant UUID
     * @throws IllegalStateException if called outside a request-scoped context
     *                               that has been initialised by
     *                               {@link TenantContextFilter}
     */
    @NonNull
    public static UUID getCurrentTenant() {
        if (!CURRENT_TENANT.isBound()) {
            throw new IllegalStateException(
                    "No tenant bound to the current scope. " +
                            "Ensure TenantContextFilter is registered in the filter chain.");
        }
        return CURRENT_TENANT.get();
    }

    /**
     * Returns {@code true} if a tenant is currently bound to this scope.
     */
    public static boolean isBound() {
        return CURRENT_TENANT.isBound();
    }
}
