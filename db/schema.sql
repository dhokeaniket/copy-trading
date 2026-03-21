CREATE TABLE IF NOT EXISTS users (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(100) UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  role VARCHAR(20) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS subscriptions (
  id BIGSERIAL PRIMARY KEY,
  master_id BIGINT NOT NULL,
  child_id BIGINT NOT NULL,
  broker VARCHAR(30) NOT NULL,
  scale DOUBLE PRECISION NOT NULL DEFAULT 1,
  active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_master ON subscriptions(master_id);

CREATE TABLE IF NOT EXISTS trade_logs (
  id BIGSERIAL PRIMARY KEY,
  masterid BIGINT,
  childid BIGINT,
  type VARCHAR(30) NOT NULL,
  status VARCHAR(30) NOT NULL,
  message TEXT,
  broker VARCHAR(30),
  reference VARCHAR(100),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trade_logs_masterid ON trade_logs(masterid);
CREATE INDEX IF NOT EXISTS idx_trade_logs_childid ON trade_logs(childid);

CREATE EXTENSION IF NOT EXISTS pgcrypto;
