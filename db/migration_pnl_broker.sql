-- Add PnL columns
ALTER TABLE trades ADD COLUMN IF NOT EXISTS realized_pnl DOUBLE PRECISION DEFAULT 0.0;
ALTER TABLE copy_logs ADD COLUMN IF NOT EXISTS realized_pnl DOUBLE PRECISION DEFAULT 0.0;

-- Create PnL snapshots table
CREATE TABLE IF NOT EXISTS user_pnl_snapshots (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  snapshot_date DATE NOT NULL,
  daily_pnl DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  total_pnl DOUBLE PRECISION NOT NULL DEFAULT 0.0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id, snapshot_date)
);

CREATE INDEX IF NOT EXISTS idx_user_pnl_date ON user_pnl_snapshots(snapshot_date);

-- Add Broker Status columns
ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS token_expiry TIMESTAMPTZ;
ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS last_sync_time TIMESTAMPTZ;
ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS last_ping_ms INTEGER;
