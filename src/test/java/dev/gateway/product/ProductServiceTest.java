package dev.gateway.product;

import dev.gateway.tenant.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ProductService}.
 *
 * <p>
 * Uses a hand-crafted in-memory stub of {@link ProductRepository} instead of
 * Mockito's annotation-based mocking. This approach avoids the Mockito
 * byte-buddy
 * limitation with mocking Spring Data JPA repository interface hierarchies on
 * Java 25 (where the inline agent cannot retransform all interfaces in the
 * hierarchy).
 *
 * <p>
 * The stub is defined as a private inner class and provides only the methods
 * exercised by the service under test.
 */
@DisplayName("ProductService (unit, hand-crafted stub)")
class ProductServiceTest {

    private InMemoryProductRepository stubRepo;
    private ProductService productService;

    @BeforeEach
    void setUp() {
        stubRepo = new InMemoryProductRepository();
        productService = new ProductService(stubRepo);
    }

    @Test
    @DisplayName("findAll() returns all products visible in the current tenant scope")
    void findAll_returnsAllProducts() throws Exception {
        UUID tenantId = UUID.randomUUID();
        stubRepo.store(new Product(tenantId, "Widget", new BigDecimal("9.99")));
        stubRepo.store(new Product(tenantId, "Gadget", new BigDecimal("19.99")));

        ScopedValue.where(TenantContext.CURRENT_TENANT, tenantId).run(() -> {
            Page<ProductResponse> responses = productService
                    .findAll(org.springframework.data.domain.PageRequest.of(0, 10));

            assertThat(responses.getContent()).hasSize(2);
            assertThat(responses.getContent()).extracting(ProductResponse::name)
                    .containsExactlyInAnyOrder("Widget", "Gadget");
        });
    }

    @Test
    @DisplayName("create() sets tenantId from TenantContext — never from request body")
    void create_setsCorrectTenantId() throws Exception {
        UUID tenantId = UUID.randomUUID();
        CreateProductRequest request = new CreateProductRequest("New Item", new BigDecimal("5.00"));

        ScopedValue.where(TenantContext.CURRENT_TENANT, tenantId).run(() -> {
            ProductResponse created = productService.create(request);

            assertThat(created.tenantId()).isEqualTo(tenantId);
            assertThat(created.name()).isEqualTo("New Item");
            assertThat(created.price()).isEqualByComparingTo("5.00");
        });
    }

    @Test
    @DisplayName("findById() throws EntityNotFoundException for an unknown ID")
    void findById_unknownId_throwsNotFound() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID unknownId = UUID.randomUUID();

        ScopedValue.where(TenantContext.CURRENT_TENANT, tenantId).run(() -> {
            assertThatThrownBy(() -> productService.findById(unknownId))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(unknownId.toString());
        });
    }

    @Test
    @DisplayName("delete() removes the product from the repository")
    void delete_removesProduct() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Product product = stubRepo.store(new Product(tenantId, "ToDelete", new BigDecimal("1.00")));

        ScopedValue.where(TenantContext.CURRENT_TENANT, tenantId).run(() -> {
            productService.delete(product.getId());
            assertThat(stubRepo.findById(product.getId())).isEmpty();
        });
    }

    // ── In-memory stub ───────────────────────────────────────────────────────

    /**
     * Minimal in-memory implementation of {@link ProductRepository} for unit tests.
     * Only implements the methods that {@link ProductService} actually calls.
     */
    private static final class InMemoryProductRepository implements ProductRepository {

        private final Map<UUID, Product> store = new LinkedHashMap<>();

        Product store(Product product) {
            UUID id = UUID.randomUUID();
            try {
                var f = Product.class.getDeclaredField("id");
                f.setAccessible(true);
                f.set(product, id);
            } catch (Exception e) {
                throw new RuntimeException("Could not set product ID in test stub", e);
            }
            store.put(id, product);
            return product;
        }

        // ── Methods called by ProductService ─────────────────────────────────

        @Override
        public List<Product> findAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public Optional<Product> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean existsById(UUID id) {
            return store.containsKey(id);
        }

        @Override
        public void deleteById(UUID id) {
            store.remove(id);
        }

        @Override
        public <S extends Product> S save(S entity) {
            if (entity.getId() == null) {
                store(entity);
            } else {
                store.put(entity.getId(), entity);
            }
            return entity;
        }

        // ── Unused JPA methods — throw UnsupportedOperationException ─────────

        @Override
        public List<Product> findAll(Sort sort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<Product> findAll(Pageable pageable) {
            List<Product> list = new ArrayList<>(store.values());
            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), list.size());
            return new org.springframework.data.domain.PageImpl<>(list.subList(start, end), pageable, list.size());
        }

        @Override
        public <S extends Product> List<S> saveAll(Iterable<S> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Product> findAllById(Iterable<UUID> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count() {
            return store.size();
        }

        @Override
        public void delete(Product entity) {
            store.remove(entity.getId());
        }

        @Override
        public void deleteAllById(Iterable<? extends UUID> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAll(Iterable<? extends Product> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAll() {
            store.clear();
        }

        @Override
        public void flush() {
        }

        @Override
        public <S extends Product> S saveAndFlush(S entity) {
            return save(entity);
        }

        @Override
        public <S extends Product> List<S> saveAllAndFlush(Iterable<S> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAllInBatch(Iterable<Product> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAllByIdInBatch(Iterable<UUID> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAllInBatch() {
            store.clear();
        }

        @Override
        public Product getOne(UUID uuid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Product getById(UUID uuid) {
            return store.get(uuid);
        }

        @Override
        public Product getReferenceById(UUID uuid) {
            return store.get(uuid);
        }

        @Override
        public <S extends Product> Optional<S> findOne(Example<S> example) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends Product> List<S> findAll(Example<S> example) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends Product> List<S> findAll(Example<S> example, Sort sort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends Product> Page<S> findAll(Example<S> example, Pageable pageable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends Product> long count(Example<S> example) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends Product> boolean exists(Example<S> example) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <S extends Product, R> R findBy(Example<S> example,
                Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
            throw new UnsupportedOperationException();
        }
    }
}
