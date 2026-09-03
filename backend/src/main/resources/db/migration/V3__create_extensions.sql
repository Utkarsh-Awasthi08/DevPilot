-- Supersedes the old V1_init_schema.sql, whose filename didn't match Flyway's
-- V<version>__<description>.sql convention (single underscore), so it was never actually
-- picked up as a versioned migration. Idempotent so it's harmless where docker-compose's
-- init-extensions.sql already created these on first container boot.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
