-- Migration: Add per-user proxy support to broker_accounts
-- Each account can optionally route ALL broker API calls through a dedicated proxy.
-- If no proxy is configured, direct connection is used (current behavior).
--
-- Run this against your database:
--   psql -U copytrading -d copytrading -f scripts/migrate_add_proxy_columns.sql

ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS proxy_host VARCHAR(255);
ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS proxy_port INTEGER;
ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS proxy_user VARCHAR(255);
ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS proxy_pass TEXT;
