package dev.gateway.product;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable read-model (response DTO) for {@link Product}.
 * Uses a Java record for conciseness and value semantics.
 */
public record ProductResponse(
        UUID id,
        UUID tenantId,
        String name,
        BigDecimal price,
        OffsetDateTime createdAt) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getTenantId(),
                product.getName(),
                product.getPrice(),
                product.getCreatedAt());
    }
}
