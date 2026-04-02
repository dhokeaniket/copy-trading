-- ============================================================
-- Ascentra Trading Platform — Database Schema
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Users table with full profile fields
CREATE TABLE IF NOT EXISTS users (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  name          VARCHAR(200) NOT NULL,
  email         VARCHAR(255) UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  role          VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN','MASTER','CHILD')),
  status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  phone         VARCHAR(30),
  two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  two_factor_secret  TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);
CREATE INDEX IF NOT EXISTS idx_users_status ON users(status);

-- Refresh tokens table for token rotation
CREATE TABLE IF NOT EXISTS refresh_tokens (
  id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked    BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_hash ON refresh_tokens(token_hash);

-- Password reset tokens
CREATE TABLE IF NOT EXISTS password_reset_tokens (
  id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  used       BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_password_reset_user ON password_reset_tokens(user_id);

-- Subscriptions table
CREATE TABLE IF NOT EXISTS subscriptions (
  id         BIGSERIAL PRIMARY KEY,
  master_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  child_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  broker     VARCHAR(30) NOT NULL,
  scale      DOUBLE PRECISION NOT NULL DEFAULT 1,
  active     BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_master ON subscriptions(master_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_child ON subscriptions(child_id);

-- Trade logs table
CREATE TABLE IF NOT EXISTS trade_logs (
  id         BIGSERIAL PRIMARY KEY,
  master_id  UUID,
  child_id   UUID,
  type       VARCHAR(30) NOT NULL,
  status     VARCHAR(30) NOT NULL,
  message    TEXT,
  broker     VARCHAR(30),
  reference  VARCHAR(100),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trade_logs_master ON trade_logs(master_id);
CREATE INDEX IF NOT EXISTS idx_trade_logs_child ON trade_logs(child_id);
CREATE INDEX IF NOT EXISTS idx_trade_logs_status ON trade_logs(status);
CREATE INDEX IF NOT EXISTS idx_trade_logs_created ON trade_logs(created_at);

-- Broker accounts (linked demat accounts)
CREATE TABLE IF NOT EXISTS broker_accounts (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  broker_id       VARCHAR(30) NOT NULL,
  client_id       VARCHAR(100) NOT NULL,
  api_key         TEXT NOT NULL,
  api_secret      TEXT NOT NULL,
  access_token    TEXT,
  nickname        VARCHAR(100),
  status          VARCHAR(30) NOT NULL DEFAULT 'LINKED' CHECK (status IN ('LINKED','ACTIVE','INACTIVE','AUTH_REQUIRED')),
  session_active  BOOLEAN NOT NULL DEFAULT FALSE,
  session_expires TIMESTAMPTZ,
  linked_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_broker_accounts_user ON broker_accounts(user_id);
CREATE INDEX IF NOT EXISTS idx_broker_accounts_broker ON broker_accounts(broker_id);
