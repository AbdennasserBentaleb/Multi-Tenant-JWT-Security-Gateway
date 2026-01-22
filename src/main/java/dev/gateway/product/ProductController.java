package dev.gateway.product;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * REST controller for the Products resource.
 *
 * <p>
 * All endpoints are tenant-scoped — the RLS policy transparently restricts
 * data access. No tenant-filtering logic resides here.
 *
 * <p>
 * Uses constructor injection (no {@code @Autowired} on fields).
 */
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Tenant-isolated product management API")
@SecurityRequirement(name = "bearerAuth")
public class ProductController {

        private final ProductService productService;

        public ProductController(ProductService productService) {
                this.productService = productService;
        }

        @GetMapping
        @Operation(summary = "List products", description = "Returns products belonging to the authenticated tenant with pagination. "
                        +
                        "PostgreSQL RLS enforces isolation — no cross-tenant data is returned.", responses = {
                                        @ApiResponse(responseCode = "200", description = "Products retrieved"),
                                        @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
                        })
        public ResponseEntity<Page<ProductResponse>> listProducts(@ParameterObject Pageable pageable) {
                return ResponseEntity.ok(productService.findAll(pageable));
        }

        @GetMapping("/{id}")
        @Operation(summary = "Get product by ID", description = "Returns a specific product. Returns 404 if it belongs to a different tenant.", responses = {
                        @ApiResponse(responseCode = "200", description = "Product found"),
                        @ApiResponse(responseCode = "404", description = "Product not found or belongs to another tenant")
        })
        public ResponseEntity<ProductResponse> getProduct(@PathVariable UUID id) {
                return ResponseEntity.ok(productService.findById(id));
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Create product", description = "Creates a new product for the authenticated tenant. " +
                        "The tenant_id is taken from the JWT — not from the request body.", responses = {
                                        @ApiResponse(responseCode = "201", description = "Product created"),
                                        @ApiResponse(responseCode = "400", description = "Validation failed"),
                                        @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
                        })
        public ResponseEntity<ProductResponse> createProduct(
                        @Valid @RequestBody CreateProductRequest request) {

                ProductResponse created = productService.create(request);
                URI location = ServletUriComponentsBuilder
                                .fromCurrentRequest()
                                .path("/{id}")
                                .buildAndExpand(created.id())
                                .toUri();
                return ResponseEntity.created(location).body(created);
        }

        @PutMapping("/{id}")
        @Operation(summary = "Update product", description = "Updates a product. RLS prevents updating another tenant's products.", responses = {
                        @ApiResponse(responseCode = "200", description = "Product updated"),
                        @ApiResponse(responseCode = "400", description = "Validation failed"),
                        @ApiResponse(responseCode = "404", description = "Product not found")
        })
        public ResponseEntity<ProductResponse> updateProduct(
                        @PathVariable UUID id,
                        @Valid @RequestBody CreateProductRequest request) {
                return ResponseEntity.ok(productService.update(id, request));
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        @Operation(summary = "Delete product", description = "Deletes a product. RLS prevents deleting another tenant's products.", responses = {
                        @ApiResponse(responseCode = "204", description = "Product deleted"),
                        @ApiResponse(responseCode = "404", description = "Product not found")
        })
        public ResponseEntity<Void> deleteProduct(@PathVariable UUID id) {
                productService.delete(id);
                return ResponseEntity.noContent().build();
        }
}
