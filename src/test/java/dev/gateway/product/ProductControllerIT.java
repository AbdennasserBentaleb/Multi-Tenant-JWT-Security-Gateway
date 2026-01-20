package dev.gateway.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gateway.tenant.JwtTenantConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test suite for the Products API.
 *
 * <p>
 * This proves the core value proposition of the entire gateway:
 * <strong>Tenant A cannot see Tenant B's data, and vice versa.</strong>
 *
 * <h2>Setup strategy</h2>
 * <ol>
 * <li>Testcontainers spins up a real PostgreSQL 16 container.</li>
 * <li>Flyway migrations run automatically (V1 schema + V2 RLS policies).</li>
 * <li>Seed data is inserted for two distinct tenants using a superuser
 * JDBC connection (bypasses RLS — only for test setup).</li>
 * <li>Each test mints a mock JWT with {@code tenant_id} claim using
 * {@code SecurityMockMvcRequestPostProcessors.jwt()}.</li>
 * <li>The test asserts that the response only contains the correct tenant's
 * rows.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Product API — Multi-Tenant RLS Integration Tests")
class ProductControllerIT {

        /** PostgreSQL 16 Alpine — matches k3s production environment. */
        @Container
        static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                        .withDatabaseName("gatewaydb")
                        .withUsername("postgres") // superuser for migrations
                        .withPassword("postgres");

        @DynamicPropertySource
        static void overrideDataSourceProperties(DynamicPropertyRegistry registry) {
                registry.add("spring.datasource.url", postgres::getJdbcUrl);
                registry.add("spring.datasource.username", () -> "postgres");
                registry.add("spring.datasource.password", () -> "postgres");
                // Disable OAuth2 issuer-uri resolution during tests
                registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                                () -> "http://localhost:9999/test-issuer");
        }

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        @BeforeEach
        void seedDatabase() {
                // Truncate and reseed before each test for determinism
                jdbcTemplate.execute("TRUNCATE TABLE products");

                // Bypass RLS for seeding — postgres superuser ignores RLS by default
                jdbcTemplate.update(
                                "INSERT INTO products (tenant_id, name, price) VALUES (?, ?, ?)",
                                TENANT_A, "Tenant A Widget", new BigDecimal("10.00"));
                jdbcTemplate.update(
                                "INSERT INTO products (tenant_id, name, price) VALUES (?, ?, ?)",
                                TENANT_A, "Tenant A Gadget", new BigDecimal("20.00"));
                jdbcTemplate.update(
                                "INSERT INTO products (tenant_id, name, price) VALUES (?, ?, ?)",
                                TENANT_B, "Tenant B Product", new BigDecimal("99.00"));
        }

        // ── Helper: build a mock JWT with a tenant_id claim ──────────────────────

        private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor tenantJwt(UUID tenantId) {
                return jwt().jwt(token -> token
                                .claim("sub", "user-" + tenantId)
                                .claim(JwtTenantConverter.TENANT_CLAIM, tenantId.toString())
                                .claim("scope", "openid"));
        }

        // ═════════════════════════════════════════════════════════════════════════
        // THE CORE TEST: Cross-tenant isolation
        // ═════════════════════════════════════════════════════════════════════════

        @Test
        @DisplayName("🔐 Tenant A can only see their own 2 products — not Tenant B's")
        void tenantA_seesOnlyTheirOwnProducts() throws Exception {
                mockMvc.perform(get("/api/v1/products")
                                .with(tenantJwt(TENANT_A)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content", hasSize(2)))
                                .andExpect(jsonPath("$.content[*].name", everyItem(containsString("Tenant A"))))
                                .andExpect(jsonPath("$.content[*].tenantId", everyItem(is(TENANT_A.toString()))));
        }

        @Test
        @DisplayName("🔐 Tenant B can only see their own 1 product — not Tenant A's")
        void tenantB_seesOnlyTheirOwnProduct() throws Exception {
                mockMvc.perform(get("/api/v1/products")
                                .with(tenantJwt(TENANT_B)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content", hasSize(1)))
                                .andExpect(jsonPath("$.content[0].name", is("Tenant B Product")))
                                .andExpect(jsonPath("$.content[0].tenantId", is(TENANT_B.toString())));
        }

        // ═════════════════════════════════════════════════════════════════════════
        // Create product — tenant_id must come from JWT, not request body
        // ═════════════════════════════════════════════════════════════════════════

        @Test
        @DisplayName("Created product is automatically tagged with JWT tenant_id")
        void createProduct_tenantIdComesFromJwt() throws Exception {
                CreateProductRequest request = new CreateProductRequest("New Item", new BigDecimal("5.00"));

                mockMvc.perform(post("/api/v1/products")
                                .with(tenantJwt(TENANT_A))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.tenantId", is(TENANT_A.toString())))
                                .andExpect(jsonPath("$.name", is("New Item")))
                                .andExpect(header().exists("Location"));
        }

        // ═════════════════════════════════════════════════════════════════════════
        // Authentication enforcement
        // ═════════════════════════════════════════════════════════════════════════

        @Test
        @DisplayName("Request without JWT returns 401 Unauthorized")
        void request_withoutJwt_returns401() throws Exception {
                mockMvc.perform(get("/api/v1/products"))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("JWT missing tenant_id claim returns 401")
        void jwt_withoutTenantClaim_returns401() throws Exception {
                mockMvc.perform(get("/api/v1/products")
                                .with(jwt().jwt(token -> token
                                                .claim("sub", "user-no-tenant")
                                                .claim("scope", "openid")
                                // deliberately omit tenant_id
                                )))
                                .andExpect(status().isUnauthorized());
        }

        // ═════════════════════════════════════════════════════════════════════════
        // Input validation
        // ═════════════════════════════════════════════════════════════════════════

        @Test
        @DisplayName("Creating product with blank name returns 400 with field error details")
        void createProduct_blankName_returns400() throws Exception {
                CreateProductRequest invalid = new CreateProductRequest("", new BigDecimal("10.00"));

                mockMvc.perform(post("/api/v1/products")
                                .with(tenantJwt(TENANT_A))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(invalid)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.title", is("Validation Failed")))
                                .andExpect(jsonPath("$.fieldErrors.name", is("Product name must not be blank")));
        }

        @Test
        @DisplayName("k3s liveness probe is reachable without authentication")
        void actuatorHealth_isPublic() throws Exception {
                mockMvc.perform(get("/actuator/health"))
                                .andExpect(status().isOk());
        }
}
