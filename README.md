# Multi-Tenant JWT Security Gateway

> Spring Boot 3.4 · Java 25 · PostgreSQL Row-Level Security · k3s

[![CI](https://github.com/YOUR_USERNAME/jwt-security-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/YOUR_USERNAME/jwt-security-gateway/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-25-orange)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-green)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)


## Problem Statement

B2B SaaS applications must guarantee that **Client A cannot see Client B's data**, even when they share a database. Most applications solve this at the *application layer* — a forgotten `WHERE` clause can leak data across tenants.

I built this gateway to implement **defence-in-depth** using two independent trust boundaries:

| Layer | Mechanism | Failure mode if bypassed |
|---|---|---|
| Application | Spring Security OAuth2 JWT validation | Request rejected as 401 |
| **Database** | **PostgreSQL Row-Level Security (RLS)** | **0 rows returned, silently** |

I wanted to make tenant isolation **impossible to bypass from the application code**, ensuring strong data isolation by design.


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
│     └─ ScopedValue.where(CURRENT_TENANT, uuid).call(...)    │
│        • Java 25 ScopedValue — immutable, auto-cleaned      │
│         (safer than ThreadLocal with Virtual Threads)        │
│                                                             │
│  3. TenantAwareDataSource (HikariCP wrapper)                │
│     └─ getConnection() → SET LOCAL app.current_tenant = ?   │
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
* Java 25 JDK (for local builds)

### 1. Clone and start services

```bash
git clone https://github.com/YOUR_USERNAME/jwt-security-gateway
cd jwt-security-gateway
docker compose up --build -d
```

This starts:

* **PostgreSQL 16** with Flyway migrations (RLS enabled automatically)
* **Keycloak 25** identity provider
* **Gateway application** on port 8080

### 2. Get a token from Keycloak

```bash
# Replace with your Keycloak admin-created user credentials
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/gateway/protocol/openid-connect/token \
  -d "grant_type=password&client_id=gateway-client&username=tenant-a-user&password=password" \
  | jq -r '.access_token')
```

### 3. Call the API

```bash
# List products (only Tenant A's products are returned)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/products

# Create a product (tenant_id is taken from JWT, not the body)
curl -X POST http://localhost:8081/api/v1/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Premium Widget", "price": 49.99}'
```

### 4. Explore the API

Swagger UI: **<http://localhost:8081/swagger-ui.html>**

Metrics: **<http://localhost:8081/actuator/prometheus>**


## Running Tests

```bash
# All tests (unit + Testcontainers integration)
mvn verify -Dspring.profiles.active=test

# Unit tests only
mvn test
```

The integration test (`ProductControllerIT`) spins up a **real PostgreSQL 16** container and proves:

> **Note for Windows Users**: Testcontainers requires a valid Docker environment. If `mvn verify` fails to find Docker, ensure Docker Desktop is running and WSL integration is properly configured. Alternatively, rely on the `docker compose` setup for end-to-end verification.

1. [Test Passed] Tenant A's JWT returns only Tenant A's rows
2. [Test Passed] Tenant B's JWT returns only Tenant B's rows
3. [Test Passed] No JWT → 401 Unauthorized
4. [Test Passed] JWT without `tenant_id` claim → 401 Unauthorized
5. [Test Passed] Invalid input → 400 with RFC 7807 ProblemDetail


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


## Key Design Decisions (ADRs)

### ADR-001: Java 25 ScopedValue over ThreadLocal

**Decision**: Use `ScopedValue` (JEP 506, finalized in Java 25) instead of `ThreadLocal`.

**Rationale**: ScopedValue is immutable once bound — a downstream component cannot accidentally overwrite the tenant. It also integrates correctly with Project Loom Virtual Threads, where `ThreadLocal` inheritance semantics cause subtle bugs at scale.

### ADR-002: RLS-first, no application-layer WHERE clauses

**Decision**: `ProductRepository` has zero explicit tenant filters. `ProductController` and `ProductService` contain no tenant isolation logic.

**Rationale**: Application-layer filtering creates two sources of truth. A single bug — a refactored method, a new developer, a code review miss — can bypass it. RLS is enforced by the database engine independently of application code. It cannot be bypassed by a SQL injection attack because the policy is evaluated after query parsing.

### ADR-003: SET LOCAL over SET

**Decision**: `SET LOCAL app.current_tenant = ?` (not `SET`).

**Rationale**: `SET LOCAL` is transaction-scoped. When the transaction commits or rolls back, PostgreSQL automatically clears the variable. This makes the approach safe with connection pooling — a connection returned to HikariCP cannot "leak" a tenant ID to the next borrower.

### ADR-004: Distroless nonroot runtime

**Decision**: `gcr.io/distroless/java25-debian12:nonroot` as the runtime base image.

**Rationale**: No shell, no package manager, and no OS utilities means no attack surface. The container runs as UID 65532 (non-root) with a read-only root filesystem. This satisfies typical strict ISMS (e.g. ISO 27001) container hardening requirements.


## Project Structure

```
├── src/main/java/dev/gateway/
│   ├── JwtGatewayApplication.java      — Spring Boot entry point
│   ├── config/
│   │   ├── SecurityConfig.java         — OAuth2 Resource Server setup
│   │   └── DataSourceConfig.java       — HikariCP + RLS connection wrapper
│   ├── tenant/
│   │   ├── TenantContext.java          — Java 25 ScopedValue holder
│   │   ├── JwtTenantConverter.java     — JWT claim extractor
│   │   └── TenantContextFilter.java    — Binds tenant to ScopedValue scope
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
│   │   ├── TenantContextTest.java      — Unit: ScopedValue behaviour
│   │   └── JwtTenantConverterTest.java — Unit: claim extraction
│   └── product/
│       ├── ProductServiceTest.java     — Unit: Mockito service tests
│       └── ProductControllerIT.java    — Integration: Testcontainers + RLS proof
├── Dockerfile                          — Multi-stage: temurin:25 → distroless
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
| Language | Java | 25 (LTS) |
| Framework | Spring Boot | 3.4.3 |
| Security | Spring Security OAuth2 RS | 6.4.x |
| Persistence | Spring Data JPA + Hibernate | 6.x |
| Database | PostgreSQL | 16+ |
| Migrations | Flyway | 10.x |
| Tests | JUnit 5 + Mockito + Testcontainers | Latest |
| Container | Distroless Java 25 | nonroot |
| Orchestration | k3s (Kubernetes) | v1.29+ |
| CI/CD | GitHub Actions | Latest |

