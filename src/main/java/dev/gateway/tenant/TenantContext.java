package dev.gateway.tenant;

import org.springframework.lang.NonNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Holds the current request's {@code tenant_id} using ThreadLocal.
 * 
 * <p>
 * This replaces the Java 25 ScopedValue implementation for compatibility with
 * Spring Boot 3.4.
 * </p>
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new InheritableThreadLocal<>();

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
        UUID tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant bound to the current scope. " +
                            "Ensure TenantContextFilter is registered in the filter chain.");
        }
        return tenantId;
    }

    /**
     * Returns {@code true} if a tenant is currently bound to this scope.
     */
    public static boolean isBound() {
        return CURRENT_TENANT.get() != null;
    }

    /**
     * Binds the given tenant identifier to the current thread.
     */
    public static void setTenantId(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    /**
     * Clears the tenant identifier from the current thread.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
