-- Run this script against your PostgreSQL database to add the required columns for the Async Entry-Price Worker

ALTER TABLE copy_logs ADD COLUMN IF NOT EXISTS entry_price NUMERIC;
ALTER TABLE copy_logs ADD COLUMN IF NOT EXISTS filled_qty INTEGER;
ALTER TABLE copy_logs ADD COLUMN IF NOT EXISTS invested_value NUMERIC;

-- Optional: Create an index to speed up the background polling
CREATE INDEX IF NOT EXISTS idx_copy_logs_pending_entry ON copy_logs(child_status) WHERE child_status = 'PLACED' AND entry_price IS NULL;
