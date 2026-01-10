# Multi-Tenant JWT Security Gateway

> Spring Boot 3.4 · Java 21 · PostgreSQL Row-Level Security · k3s

[![CI](https://github.com/AbdennasserBentaleb/jwt-security-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/AbdennasserBentaleb/jwt-security-gateway/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-green)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)


## Problem Statement

B2B SaaS applications must guarantee that **Client A cannot see Client B's data**, even when they share a database. Most applications solve this at the *application layer* — a forgotten `WHERE` clause can leak data across tenants.

I built this gateway to implement **defence-in-depth** using two independent trust boundaries:

| Layer | Mechanism | Failure mode if bypassed |
|---|---|---|
| Application | Spring Security OAuth2 JWT validation | Request rejected as 401 |
| **Database** | **PostgreSQL Row-Level Security (RLS)** | **0 rows returned, silently** |

This repository demonstrates how to make tenant isolation impossible to bypass from the application code, ensuring strong data isolation by design.


## Architecture

```
Client A (JWT: tenant_id=A)          Client B (JWT: tenant_id=B)
           │                                       │
           ▼                                       ▼
┌─────────────────────────────────────────────────────────────┐
│                   Spring Boot Gateway                        │
│                                                             │
│  1. BearerTokenAuthenticationFilter                         │
│     └─ JwtTenantConverter.convert()                         │
│        • Validates JWT signature via JWKS                   │
│        • Extracts & validates tenant_id claim (UUID)        │
│        • Stores UUID in JwtAuthenticationToken.details      │
│                                                             │
│  2. TenantContextFilter                                     │
│     └─ TenantContext.setTenantId(uuid)                      │
│        • Standard ThreadLocal — reliable context            │
│         (Safely cleared in try-finally loop)                 │
│                                                             │
│  3. TenantAwareDataSource (HikariCP wrapper)                │
│     └─ getConnection() → SET SESSION app.current_tenant = ? │
│        • Every JDBC connection is tenant-stamped            │
│                                                             │
│  4. ProductController → ProductService → ProductRepository   │
│     └─ No WHERE clause — RLS handles it transparently       │
└─────────────────────────────────────────────────────────────┘
                              │
         ┌────────────────────▼────────────────────┐
         │            PostgreSQL 16                  │
         │                                           │
         │  CREATE POLICY tenant_isolation_policy    │
         │    ON products                            │
         │    USING (tenant_id =                     │
         │      current_setting('app.current_tenant  │
         │      ', true)::uuid)                      │
         │                                           │
         │  Even a SQL injection attack cannot        │
         │  cross tenant boundaries.                  │
         └───────────────────────────────────────────┘
```


## Quick Start (Local)

### Prerequisites

* Docker Desktop or Docker Engine
* Java 21 JDK (for local builds)

### 1. Clone and start services

```bash
git clone https://github.com/AbdennasserBentaleb/jwt-security-gateway
cd jwt-security-gateway
docker compose up --build -d
```

This starts:

* **PostgreSQL 16** with Flyway migrations (RLS enabled automatically)
* **Keycloak 25** identity provider
* **Gateway application** on port 8080

### 2. Available Credentials

The repository includes a pre-configured Keycloak realm (`docker/keycloak/realm-export.json`) with the following accounts:

| Account Type | Username | Password | Purpose |
|---|---|---|---|
| **Keycloak Admin** | `admin` | `admin` | Manage realms at `http://localhost:8180` |
| **Tenant A API User** | `tenant-a-user` | `password` | Consumer account bound to Tenant A |
| **Tenant B API User** | `tenant-b-user` | `password` | Consumer account bound to Tenant B |

### 3. Test in the Browser (Recommended)

The project includes a built-in Premium Dashboard to test data isolation visually!

1. Open your browser and navigate to **<http://localhost:8081/>**
2. Select a tenant user from the dropdown.
3. Enter `password` and click "Authenticate via Keycloak".
4. You will see the decoded JWT containing your scoped `tenant_id`.
5. Create a product (e.g., "Premium Widget").
6. Sign out and log in as the other tenant to prove you cannot see their data!

### 4. Advanced API Testing (Manual)

*(Note: The database starts empty. You must create a product first before the GET request will return anything!)*

**Option A: Using Standard Bash (Mac/Linux)**
```bash
# 1. Get a token for Tenant A
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/gateway/protocol/openid-connect/token \
  -d "grant_type=password&client_id=gateway-client&username=tenant-a-user&password=password" \
  | jq -r '.access_token')

# 2. Create a product (tenant_id is taken automatically from the JWT)
curl -X POST http://localhost:8081/api/v1/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Premium Widget", "price": 49.99}'

# 3. List products (only Tenant A's products are returned)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/products
```

**Option B: Using Windows PowerShell**
Because Windows PowerShell intercepts `curl`, use these exact commands:
```powershell
# 1. Get a token for Tenant A
$response = Invoke-RestMethod -Uri "http://localhost:8180/realms/gateway/protocol/openid-connect/token" -Method Post -Body @{
    grant_type = "password"
    client_id  = "gateway-client"
    username   = "tenant-a-user"
    password   = "password"
}
$token = $response.access_token

# 2. Create a product
Invoke-RestMethod -Uri "http://localhost:8081/api/v1/products" -Method Post -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" -Body '{"name": "Premium Widget", "price": 49.99}'

# 3. List products
Invoke-RestMethod -Uri "http://localhost:8081/api/v1/products" -Method Get -Headers @{ Authorization = "Bearer $token" }
```

**Option C: Using Postman / Insomnia**
1. Make a `POST` request to `http://localhost:8180/realms/gateway/protocol/openid-connect/token` with the `x-www-form-urlencoded` body: `grant_type=password`, `client_id=gateway-client`, `username=tenant-a-user`, `password=password`. 
2. Copy the `access_token` from the response.
3. Make `GET` or `POST` requests to `http://localhost:8081/api/v1/products` adding the header `Authorization: Bearer <your_token>`.

### 5. Common Testing Pitfalls (Troubleshooting)

**"I get a 401 Unauthorized error when visiting the Gateway API endpoints directly in my web browser."**
The Gateway (`http://localhost:8081/api/v1/products`) is a stateless backend API. You **cannot** test these JSON endpoints directly in a URL bar because the browser does not attach the necessary `Authorization: Bearer <token>` HTTP header. You *must* use the built-in Dashboard (`http://localhost:8081/`), or a tool like Postman to provide the token.

**"I can't log into the Keycloak control panel with `tenant-a-user`."**
The Keycloak Admin Console runs on port **8180** (`http://localhost:8180`), not 8080. 
Furthermore, `tenant-a-user` is exclusively an **API Consumer** account. It does not have admin privileges. Use the `admin` / `admin` credentials listed in the table above.

### 6. Explore the API

Swagger UI: **<http://localhost:8081/swagger-ui.html>**

Metrics: **<http://localhost:8081/actuator/prometheus>**


## Running Tests

```bash
# All tests (unit + Testcontainers integration)
mvn verify -Dspring.profiles.active=test

# Unit tests only
mvn test
```

The integration test (`ProductControllerIT`) runs against the **real PostgreSQL 16** container (spun up via `docker-compose`) and proves:

> **Note**: Testcontainers has been bypassed in favor of local `docker-compose` due to dynamic Windows Docker client connection instability. Ensure `docker-compose up -d` is running prior to issuing `mvn verify`.

1. ✅ CI Passed Tenant A's JWT returns only Tenant A's rows
2. ✅ CI Passed Tenant B's JWT returns only Tenant B's rows
3. ✅ CI Passed No JWT → 401 Unauthorized
4. ✅ CI Passed JWT without `tenant_id` claim → 401 Unauthorized
5. ✅ CI Passed Invalid input → 400 with RFC 7807 ProblemDetail


## Deploying to k3s

```bash
# Apply all manifests
kubectl apply -f k8s/

# Verify deployment
kubectl rollout status deployment/jwt-security-gateway -n jwt-gateway

# Check liveness/readiness
kubectl get pods -n jwt-gateway
```

Update `k8s/configmap.yaml` with your actual Keycloak issuer URI and database host before deploying.


## Architecture Decisions & Trade-offs

### 1. Java 21 ThreadLocal for Stability

**Decision**: Use `ThreadLocal` on Java 21 LTS instead of Java 25 `ScopedValues`.

**Trade-off**: While `ScopedValue` offers a mathematically safer immutability setup for virtual threads, it suffers from parsing incompatibilities with Spring Boot 3.4.x ASM class readers, systematically breaking `@SpringBootTest` testing architectures. Opting for established `ThreadLocal` structures guarantees 100% operational functionality and allows robust, E2E functional testing without sacrificing runtime isolation.

### 2. RLS-first, no application-layer WHERE clauses

**Decision**: Zero explicit tenant filters in the Application layer (JPA).

**Trade-off**: This guarantees security at the database engine level and nullifies SQL injection risks bypassing tenant boundaries. However, it tightly couples our isolation mechanism to PostgreSQL. We cannot easily migrate to a NoSQL datastore or another RDBMS without rewriting the tenancy model. Furthermore, managing RLS migrations via Flyway adds operational overhead when schemas evolve.

### 3. Java Runtime vs. Native AOT

**Decision**: Run on the standard JVM instead of GraalVM Native Image.

**Trade-off**: For an API gateway, latency is critical. We accept minor Garbage Collection (GC) pauses and higher baseline memory footprint (~256MB) to retain extensive profiling capabilities (JFR) and faster build times. While GraalVM Native Image would eliminate warmup and lower memory, it complicates our CI/CD pipeline and diminishes the observability required for a core security gateway.

### 4. Distroless nonroot runtime

**Decision**: `gcr.io/distroless/java21-debian12:nonroot` as the runtime base image.

**Trade-off**: Running as UID 65532 with a read-only root filesystem satisfies strict ISMS (e.g., ISO 27001) container hardening requirements. The trade-off is zero debugging ease: there is no shell, no `curl`, and no `bash`. If the pod misbehaves in production, we must rely entirely on our Prometheus metrics and remote logging, as `kubectl exec` is practically useless.


## Project Structure

```
├── src/main/java/dev/gateway/
│   ├── JwtGatewayApplication.java      — Spring Boot entry point
│   ├── config/
│   │   ├── SecurityConfig.java         — OAuth2 Resource Server setup
│   │   └── DataSourceConfig.java       — HikariCP + RLS connection wrapper
│   ├── tenant/
│   │   ├── TenantContext.java          — ThreadLocal holder
│   │   ├── JwtTenantConverter.java     — JWT claim extractor
│   │   └── TenantContextFilter.java    — Binds tenant across HTTP Request
│   ├── product/
│   │   ├── Product.java                — JPA Entity
│   │   ├── ProductRepository.java      — Spring Data JPA (no filters!)
│   │   ├── ProductService.java         — Business logic
│   │   ├── ProductController.java      — REST API + OpenAPI docs
│   │   ├── CreateProductRequest.java   — Write DTO (Java record)
│   │   └── ProductResponse.java        — Read DTO (Java record)
│   └── exception/
│       └── GlobalExceptionHandler.java — RFC 7807 ProblemDetail
├── src/main/resources/
│   ├── application.yml                 — 12-Factor: env-var driven config
│   └── db/migration/
│       ├── V1__init_schema.sql         — Products table
│       └── V2__enable_rls.sql          — RLS policy
├── src/test/java/dev/gateway/
│   ├── tenant/
│   │   ├── TenantContextTest.java      — Unit: ThreadLocal behaviour
│   │   └── JwtTenantConverterTest.java — Unit: claim extraction
│   └── product/
│       ├── ProductServiceTest.java     — Unit: Mockito service tests
│       └── ProductControllerIT.java    — Integration: Local Postgres + RLS proof
├── Dockerfile                          — Multi-stage: temurin:21 → distroless
├── docker-compose.yml                  — Local dev: app + postgres + keycloak
├── k8s/                                — k3s manifests
│   ├── namespace.yaml
│   ├── deployment.yaml                 — Non-root, read-only fs, probes
│   ├── service.yaml
│   ├── configmap.yaml
│   └── secret.yaml
└── .github/workflows/ci.yml           — GitHub Actions: test → push to GHCR
```


## Security Highlights

| Feature | Implementation |
|---|---|
| JWT signature validation | Keycloak JWKS auto-discovered via `issuer-uri` |
| Tenant claim validation | UUID parsing + missing claim rejection (401) |
| Data isolation | PostgreSQL RLS — FORCE ROW LEVEL SECURITY |
| Transport security | HTTPS via Traefik ingress (k3s default) |
| Cloud-Native Ready | Prometheus metrics, Pageable API results, full CORS setup |
| Container hardening | Distroless, non-root, read-only FS, caps dropped |
| Secret management | Kubernetes Secrets (template for Sealed Secrets) |
| Stateless sessions | No HTTP session — JWT-only (12-Factor) |


## Technology Stack

| Component | Technology | Version |
|---|---|---|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.4.3 |
| Security | Spring Security OAuth2 RS | 6.4.x |
| Persistence | Spring Data JPA + Hibernate | 6.x |
| Database | PostgreSQL | 16+ |
| Migrations | Flyway | 10.x |
| Tests | JUnit 5 + Mockito | Latest |
| Container | Distroless Java 21 | nonroot |
| Orchestration | k3s (Kubernetes) | v1.29+ |
| CI/CD | GitHub Actions | Latest |

