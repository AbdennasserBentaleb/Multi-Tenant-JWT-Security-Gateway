package dev.gateway.tenant;

import org.springframework.lang.NonNull;
import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    @NonNull
    public static UUID getCurrentTenant() {
        UUID tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant bound in the current scope. Ensure TenantContextFilter is registered.");
        }
        return tenantId;
    }

    public static void setTenantId(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }

    public static boolean isBound() {
        return CURRENT_TENANT.get() != null;
    }
}
