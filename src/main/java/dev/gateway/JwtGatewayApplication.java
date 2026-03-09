package dev.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Multi-Tenant JWT Security Gateway
 *
 * <p>A Spring Boot 3.4 / Java 21 microservice that enforces multi-tenant data
 * isolation using OAuth2 JWTs and PostgreSQL Row-Level Security (RLS).
 *
 * <p>Architecture highlights:
 * <ul>
 *   <li>OAuth2 Resource Server validates every inbound JWT</li>
 *   <li>Custom {@link dev.gateway.tenant.JwtTenantConverter} extracts the
 *       {@code tenant_id} claim and stores it in a Java 21 {@link ThreadLocal}</li>
 *   <li>{@link dev.gateway.tenant.TenantContextFilter} writes the tenant into
 *       the JDBC session via {@code SET LOCAL app.current_tenant = ?}</li>
 *   <li>PostgreSQL RLS policies enforce row-level isolation independently of
 *       the application layer — defence-in-depth per DSGVO Art. 25</li>
 * </ul>
 */
@SpringBootApplication
public class JwtGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(JwtGatewayApplication.class, args);
    }
}
