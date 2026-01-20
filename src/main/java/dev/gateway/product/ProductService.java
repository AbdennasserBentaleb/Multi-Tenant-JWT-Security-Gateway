package dev.gateway.product;

import dev.gateway.tenant.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Business logic for {@link Product}.
 *
 * <p>
 * Constructor injection is used for all dependencies (no {@code @Autowired}
 * on fields) in compliance with project constraints.
 *
 * <p>
 * Note that no explicit tenant filtering is done in this class. The
 * {@link ProductRepository} queries are already scoped by PostgreSQL RLS using
 * the JDBC session variable set by
 * {@link dev.gateway.config.DataSourceConfig.TenantAwareDataSource}.
 */
@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Returns a page of products visible to the current tenant.
     * RLS silently filters rows — no WHERE clause needed.
     */
    public Page<ProductResponse> findAll(Pageable pageable) {
        return productRepository.findAll(pageable)
                .map(ProductResponse::from);
    }

    /**
     * Finds a product by ID. Returns only if it belongs to the current tenant;
     * PostgreSQL RLS will return 0 rows for cross-tenant access (not a 403).
     */
    public ProductResponse findById(UUID id) {
        return productRepository.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Product not found: " + id));
    }

    /**
     * Creates a new product for the current tenant.
     * The tenant ID is taken from {@link TenantContext} — never from the request
     * body.
     */
    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        UUID tenantId = TenantContext.getCurrentTenant();
        Product product = new Product(tenantId, request.name(), request.price());
        Product saved = productRepository.save(product);
        return ProductResponse.from(saved);
    }

    /**
     * Updates a product. RLS ensures only the owning tenant can update their rows.
     */
    @Transactional
    public ProductResponse update(UUID id, CreateProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Product not found: " + id));
        product.setName(request.name());
        product.setPrice(request.price());
        return ProductResponse.from(productRepository.save(product));
    }

    /**
     * Deletes a product. RLS prevents cross-tenant deletion.
     */
    @Transactional
    public void delete(UUID id) {
        if (!productRepository.existsById(id)) {
            throw new EntityNotFoundException("Product not found: " + id);
        }
        productRepository.deleteById(id);
    }
}
