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

-- Subscriptions table (master-child copy trading links)
CREATE TABLE IF NOT EXISTS subscriptions (
  id                BIGSERIAL PRIMARY KEY,
  master_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  child_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  broker_account_id UUID REFERENCES broker_accounts(id),
  scaling_factor    DOUBLE PRECISION NOT NULL DEFAULT 1.0,
  copying_status    VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (copying_status IN ('ACTIVE','PAUSED','INACTIVE','PENDING_APPROVAL','REJECTED')),
  approved_once     BOOLEAN NOT NULL DEFAULT FALSE,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_master ON subscriptions(master_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_child ON subscriptions(child_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_subscriptions_unique ON subscriptions(master_id, child_id);

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

-- ============================================================
-- Copy Logs (detailed copy execution logs)
-- ============================================================
CREATE TABLE IF NOT EXISTS copy_logs (
  id               BIGSERIAL PRIMARY KEY,
  master_id        UUID,
  child_id         UUID,
  master_trade_id  VARCHAR(100),
  symbol           VARCHAR(50),
  qty              INTEGER,
  trade_type       VARCHAR(10),
  master_status    VARCHAR(30),
  child_status     VARCHAR(30),
  error_message    TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_copy_logs_master ON copy_logs(master_id);
CREATE INDEX IF NOT EXISTS idx_copy_logs_child ON copy_logs(child_id);
CREATE INDEX IF NOT EXISTS idx_copy_logs_created ON copy_logs(created_at);

-- ============================================================
-- Notifications
-- ============================================================
CREATE TABLE IF NOT EXISTS notifications (
  id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type       VARCHAR(50) NOT NULL,
  title      VARCHAR(200) NOT NULL,
  message    TEXT,
  read       BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_read ON notifications(user_id, read);

-- ============================================================
-- Master Active Accounts
-- ============================================================
CREATE TABLE IF NOT EXISTS master_active_accounts (
  master_id        UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  broker_account_id UUID NOT NULL REFERENCES broker_accounts(id),
  activated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- Trades (internal trade records for the trade engine)
-- ============================================================
CREATE TABLE IF NOT EXISTS trades (
  id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id          UUID NOT NULL,
  broker_account_id UUID NOT NULL,
  broker_order_id  VARCHAR(100),
  instrument       VARCHAR(100) NOT NULL,
  exchange         VARCHAR(10) NOT NULL DEFAULT 'NSE',
  segment          VARCHAR(10) NOT NULL DEFAULT 'EQUITY',
  order_type       VARCHAR(20) NOT NULL DEFAULT 'MARKET',
  transaction_type VARCHAR(10) NOT NULL,
  quantity         INTEGER NOT NULL,
  price            DOUBLE PRECISION NOT NULL DEFAULT 0,
  trigger_price    DOUBLE PRECISION,
  product          VARCHAR(10) NOT NULL DEFAULT 'MIS',
  validity         VARCHAR(10) DEFAULT 'DAY',
  status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  replications_triggered INTEGER DEFAULT 0,
  placed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  executed_at      TIMESTAMPTZ,
  cancelled_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_trades_user ON trades(user_id);
CREATE INDEX IF NOT EXISTS idx_trades_status ON trades(status);
CREATE INDEX IF NOT EXISTS idx_trades_placed ON trades(placed_at);

-- ============================================================
-- Risk Rules (per-user risk limits)
-- ============================================================
CREATE TABLE IF NOT EXISTS risk_rules (
  user_id               UUID PRIMARY KEY,
  max_trades_per_day    INTEGER NOT NULL DEFAULT 50,
  max_open_positions    INTEGER NOT NULL DEFAULT 20,
  max_capital_exposure  DOUBLE PRECISION NOT NULL DEFAULT 80,
  margin_check_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- Broker Error Logs
-- ============================================================
CREATE TABLE IF NOT EXISTS broker_error_logs (
  id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id           UUID NOT NULL,
  broker_account_id UUID,
  broker_name       VARCHAR(30),
  error_code        VARCHAR(50),
  error_message     TEXT,
  trade_id          UUID,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_broker_errors_user ON broker_error_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_broker_errors_created ON broker_error_logs(created_at);
