-- Docker init script: create the non-superuser application role
-- This runs ONCE when the postgres container is first started.
-- Flyway migrations (V2) grant object-level permissions to this role.

CREATE USER gateway_user WITH PASSWORD 'changeme';
-- CREATE DATABASE gatewaydb OWNER postgres; -- Handled by POSTGRES_DB env var
GRANT ALL PRIVILEGES ON DATABASE gatewaydb TO gateway_user;
GRANT ALL ON SCHEMA public TO gateway_user;
\c gatewaydb
CREATE EXTENSION IF NOT EXISTS pgcrypto;
