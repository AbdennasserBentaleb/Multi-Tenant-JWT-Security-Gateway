package dev.gateway.product;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents a product belonging to a specific tenant.
 *
 * <p>
 * Note: the {@code tenant_id} column participates in PostgreSQL RLS so that
 * the database engine itself enforces data isolation. Application code should
 * <strong>never</strong> add an explicit {@code WHERE tenant_id = ?} filter —
 * that would create a false sense of security that could be bypassed.
 * The RLS policy is the single source of truth.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Populated by the service layer from {@link dev.gateway.tenant.TenantContext}.
     * Must match the RLS policy's {@code app.current_tenant} session variable.
     */
    @NotNull
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @NotBlank
    @Column(name = "name", nullable = false)
    private String name;

    @NotNull
    @DecimalMin(value = "0.00", inclusive = false)
    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Product() {
        // JPA no-args constructor
    }

    public Product(UUID tenantId, String name, BigDecimal price) {
        this.tenantId = tenantId;
        this.name = name;
        this.price = price;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}
