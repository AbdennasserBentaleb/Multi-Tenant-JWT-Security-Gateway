package dev.gateway.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Write-model (request DTO) for creating or updating a {@link Product}.
 * Uses a Java record with Bean Validation annotations.
 *
 * <p>
 * Notice: there is no {@code tenantId} field here. The service layer reads the
 * tenant from {@link dev.gateway.tenant.TenantContext} to prevent tenant
 * spoofing
 * via the request body.
 */
public record CreateProductRequest(

        @NotBlank(message = "Product name must not be blank") @Size(max = 255, message = "Product name must be at most 255 characters") String name,

        @NotNull(message = "Price is required") @DecimalMin(value = "0.01", message = "Price must be greater than zero") BigDecimal price

) {
}
