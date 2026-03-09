# Product Requirements Document (PRD)

## 1. Introduction
The Multi-Tenant JWT Security Gateway is a backend architectural pattern designed to enforce robust data isolation in B2B SaaS applications. It leverages JSON Web Tokens (JWT) for authentication and PostgreSQL Row-Level Security (RLS) for data-layer authorization, ensuring that tenants cannot access each other's data even if application-level bugs occur.

## 2. Objective
To build a secure, stateless, and scalable gateway that guarantees strict tenant isolation by pushing the authorization logic down to the database engine.

## 3. Target Audience
* B2B SaaS organizations requiring strict data compliance (e.g., SOC2, ISO 27001).
* Backend engineering teams looking for a defense-in-depth architecture.

## 4. Key Features
* **Stateless Authentication**: Uses Keycloak as the Identity Provider (IdP) to issue JWTs.
* **Tenant Context Extraction**: Intercepts requests, validates the JWT, and extracts the `tenant_id`.
* **Java 21 ThreadLocal**: Uses standard request-scoped concurrency features for context propagation.
* **Database-Level Isolation**: Applies PostgreSQL Row-Level Security via `SET LOCAL`.
* **Cloud-Native Deployment**: Packaged as a distroless container, ready for Kubernetes (k3s).

## 5. Security Requirements
* All database connections must be stamped with the current tenant ID.
* Fallback policies must ensure 0 rows are returned if the tenant context is missing.
* Containers must run as non-root users.
* Read-only file systems must be used in container deployments.

## 6. Success Metrics
* 0% cross-tenant data leakage under simulated SQL injection or application logic bypass attacks.
* High performance with minimal overhead added to database connections.
* Clean integration with standard OIDC/OAuth2 Identity Providers.

