# Architecture & Design Decisions

Here are the architectural choices I made for the **Multi-Tenant JWT Security Gateway**.

My fundamental goal for this project was to implement a defence-in-depth security model for B2B multi-tenant applications. Unlike typical setups where tenant isolation is solely managed by application logic (which is prone to developer error), this gateway leverages **PostgreSQL Row-Level Security (RLS)** as the ultimate source of truth for data access.

## High-Level Architecture

The system consists of three main components:

1. **Keycloak**: Acts as the Identity Provider (IdP). It authenticates users and issues OAuth2 JSON Web Tokens (JWTs). These tokens contain a custom claim (`tenant_id`) that binds the user to a specific organization.
2. **Spring Boot Gateway**: A stateless Java 21 service. It validates the JWT signature, extracts the `tenant_id`, and manages the connection to the database.
3. **PostgreSQL**: The relational database natively enforcing isolation via Row-Level Security policies.

### Request Flow

1. A client application (e.g., a frontend SPA) sends an HTTP request with an `Authorization: Bearer <JWT>` header.
2. The `BearerTokenAuthenticationFilter` in Spring Security intercepts the request. It fetches the public keys from Keycloak's JWKS endpoint to cryptographically verify the token.
3. The custom `JwtTenantConverter` parses the validated JWT, extracts the `tenant_id` UUID claim, and binds it to the current execution thread.
4. An application-level filter (`TenantContextFilter`) wraps the request in a `ThreadLocal` holding the `tenant_id`.
5. When the application needs to interact with the database, the `TenantAwareDataSource` borrows a connection from the HikariCP pool and immediately executes `SET SESSION app.current_tenant = '<tenant_id>'` within the connection's transaction scope.
6. The application performs standard JPA/Hibernate queries (e.g., `SELECT * FROM products`). *Notice that no `WHERE tenant_id = ?` clause is explicitly written in the code.*
7. PostgreSQL receives the query. Before execution, its RLS engine transforms the query, essentially appending `WHERE tenant_id = current_setting('app.current_tenant')::uuid`.
8. The database returns *only* the rows that the current tenant is authorized to see. If a user attempts to fetch a record belonging to another tenant by passing a specific ID, PostgreSQL will silently return 0 rows.

## Key Design Decisions

### 1. Database-Level Isolation (PostgreSQL RLS)

In conventional applications, every repository method must explicitly filter by tenant ID. A single forgotten `WHERE` clause—due to refactoring, a junior developer's mistake, or a code review oversight—leads to cross-tenant data leaks.
By pushing isolation to the database engine, we achieve structural safety. Even if a SQL injection vulnerability existed in the application, the attacker would be contrained by the RLS policy evaluated at the database level.

### 2. Transaction-Scoped Variables (`SET SESSION`)

I used `SET SESSION` rather than `SET LOCAL` due to how Spring Boot Data handles connection auto-commits. While `SET LOCAL` is transaction-scoped, Spring often executes database initialization routines outside of explicitly demarcated transaction boundaries (which would cause `SET LOCAL` to silently be ignored). To prevent cross-tenant data leaks in the connection pool (HikariCP), the `TenantAwareDataSource` rigidly sets the session variable immediately when a connection is retrieved, and safely clears it when returned.

### 3. Java 21 Virtual Threads & `ThreadLocal`

While modern Java 25 `ScopedValue` provides an immutable, safely bounded context, it lacks robust support in Spring Boot 6.2's core ASM class parsers, severely breaking Integration Tests. I opted to utilize standard `ThreadLocal` running on Java 21 (LTS) paired with Virtual Threads to retain 100% stable integration and maximum framework compatibility.

### 4. Stateless Sessions

The Gateway adheres strictly to the 12-Factor App methodology regarding statelessness. There is no HTTP session state or sticky routing. All necessary authorization state is encapsulated securely within the signed JWT. This allows the application containers to be horizontally scaled infinitely behind a load balancer without configuration overhead.

### 5. Docker Distroless Non-Root Containers

Security isn't just about application code; it's about the runtime environment. The Spring Boot application is packaged in a Google Distroless base image running as a non-root user (`UID 65532`).
Because the image lacks a shell (like `bash`), package managers (`apt`), or common utilities, its attack surface is minimized. It also mounts the root filesystem as read-only, satisfying ISMS and compliance requirements (e.g., ISO 27001).

## Preparing for Production

* **Pagination**: API endpoints implement Spring Data `Pageable` to prevent memory exhaustion when querying large tables.
* **Observability**: Prometheus metrics (`micrometer-registry-prometheus`) act alongside K3s liveness and readiness probes to provide complete operational visibility.
* **CORS Configuration**: Configured to securely handle cross-origin requests from web clients, a necessity for decoupled backend API architectures.

