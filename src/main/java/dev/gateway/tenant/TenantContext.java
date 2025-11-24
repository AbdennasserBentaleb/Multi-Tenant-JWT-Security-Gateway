package dev.gateway.tenant;

import org.springframework.lang.NonNull;
import java.util.UUID;

public final class TenantContext {

    public static final ScopedValue<UUID> CURRENT_TENANT = ScopedValue.newInstance();

    private TenantContext() {
    }

    @NonNull
    public static UUID getCurrentTenant() {
        if (!CURRENT_TENANT.isBound()) {
            throw new IllegalStateException(
                    "No tenant bound in the current scope. Ensure TenantContextFilter is registered.");
        }
        return CURRENT_TENANT.get();
    }

    public static boolean isBound() {
        return CURRENT_TENANT.isBound();
    }
}
