-- V2: Enable PostgreSQL Row-Level Security
-- Data isolation enforced at the database layer (defence-in-depth).
--
-- Threat model:
--   Even if the application layer is compromised (e.g., a developer accidentally
--   removes a WHERE clause), the database engine will still silently filter rows
--   to the current tenant. This is defence-in-depth per DSGVO Art. 25.
--
-- How it works:
--   1. The TenantAwareDataSource executes:
--        SET LOCAL app.current_tenant = '<uuid>'
--      on every JDBC connection before the query runs.
--   2. The USING clause below reads that session variable.
--   3. PostgreSQL only returns rows where tenant_id matches.
-- ─────────────────────────────────────────────────────────────────────

-- Enable RLS on the products table
ALTER TABLE products ENABLE ROW LEVEL SECURITY;

-- FORCE RLS applies the policy even to the table owner.
-- This prevents accidental bypass by a superuser-owned application role.
ALTER TABLE products FORCE ROW LEVEL SECURITY;

-- ─────────────────────────────────────────────────────────────────────
-- Application role
-- The connection pool connects as gateway_user (non-superuser).
-- Superuser connections (e.g., Flyway migrations) bypass RLS by default,
-- which is why we run migrations as a separate admin role.
-- ─────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'gateway_user') THEN
        CREATE ROLE gateway_user LOGIN PASSWORD 'local_dev_password_only';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO gateway_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON products TO gateway_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO gateway_user;

-- ─────────────────────────────────────────────────────────────────────
-- RLS Policy: tenant_isolation_policy
--
-- USING  → filters rows returned by SELECT, UPDATE, DELETE
-- WITH CHECK → validates rows inserted or updated
--
-- current_setting('app.current_tenant', true) — the 'true' means "return
-- NULL instead of throwing an error" if the var is not set. We cast it to
-- UUID for a type-safe comparison with the tenant_id column.
-- ─────────────────────────────────────────────────────────────────────
CREATE POLICY tenant_isolation_policy ON products
    AS PERMISSIVE
    FOR ALL
    TO gateway_user
    USING (
        tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid
    )
    WITH CHECK (
        tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid
    );

COMMENT ON POLICY tenant_isolation_policy ON products IS
    'Enforces multi-tenant Row-Level Security. Rows are only visible when '
    'tenant_id matches the app.current_tenant JDBC session variable. '
    'Enforces row-level multi-tenancy to guarantee data isolation.';
