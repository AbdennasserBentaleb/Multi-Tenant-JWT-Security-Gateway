-- V1: Initial schema
-- Uses pgcrypto for gen_random_uuid() on PostgreSQL 16
-- (uuid-ossp is the older alternative)

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─────────────────────────────────────────────────────────────────────
-- Products table
-- All rows must have a tenant_id; the RLS policy in V2 enforces
-- that each JDBC session can only see rows matching its tenant.
-- ─────────────────────────────────────────────────────────────────────
CREATE TABLE products (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID            NOT NULL,
    name        VARCHAR(255)    NOT NULL,
    price       NUMERIC(12, 2)  NOT NULL CHECK (price > 0),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- Index to accelerate tenant-scoped queries (used in RLS USING clause)
CREATE INDEX idx_products_tenant_id ON products (tenant_id);

COMMENT ON TABLE products IS
    'Multi-tenant product catalogue. Row-Level Security in V2 enforces isolation.';
COMMENT ON COLUMN products.tenant_id IS
    'UUID of the owning tenant. Matches app.current_tenant PostgreSQL session variable.';
