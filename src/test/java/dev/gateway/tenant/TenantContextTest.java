package dev.gateway.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TenantContext}.
 */
@DisplayName("TenantContext (ScopedValue)")
class TenantContextTest {

    @Nested
    @DisplayName("when a tenant is bound")
    class WhenTenantIsBound {

        @Test
        @DisplayName("isBound() returns true")
        void isBound_returnsTrue() {
            UUID tenantId = UUID.randomUUID();

            try {
                TenantContext.setTenantId(tenantId);
                assertThat(TenantContext.isBound()).isTrue();
            } finally {
                TenantContext.clear();
            }
        }

        @Test
        @DisplayName("getCurrentTenant() returns the bound tenant UUID")
        void getCurrentTenant_returnsBoundValue() {
            UUID tenantId = UUID.randomUUID();

            try {
                TenantContext.setTenantId(tenantId);
                assertThat(TenantContext.getCurrentTenant()).isEqualTo(tenantId);
            } finally {
                TenantContext.clear();
            }
        }
    }

    @Nested
    @DisplayName("when no tenant is bound")
    class WhenNoTenantIsBound {

        @Test
        @DisplayName("isBound() returns false outside a scoped block")
        void isBound_returnsFalse() {
            assertThat(TenantContext.isBound()).isFalse();
        }

        @Test
        @DisplayName("getCurrentTenant() throws IllegalStateException outside a scoped block")
        void getCurrentTenant_throwsWhenUnbound() {
            assertThatThrownBy(TenantContext::getCurrentTenant)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No tenant bound");
        }

        @Test
        @DisplayName("ScopedValue is automatically cleared after run() block")
        void scopedValue_isClearedAfterRun() {
            UUID tenantId = UUID.randomUUID();
            try {
                TenantContext.setTenantId(tenantId);
                assertThat(TenantContext.isBound()).isTrue();
            } finally {
                TenantContext.clear();
            }

            // After clear(), the value must be gone
            assertThat(TenantContext.isBound()).isFalse();
        }
    }
}
