package com.copytrading.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

@Component
public class DatabaseMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);
    private final DatabaseClient databaseClient;

    public DatabaseMigrationRunner(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Checking and applying pending database migrations...");

        // ── Step 1: Core schema additions ──
        String step1 = """
            ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS is_copy_enable BOOLEAN NOT NULL DEFAULT TRUE;
            ALTER TABLE trades ADD COLUMN IF NOT EXISTS realized_pnl DOUBLE PRECISION DEFAULT 0.0;
            ALTER TABLE copy_logs ADD COLUMN IF NOT EXISTS realized_pnl DOUBLE PRECISION DEFAULT 0.0;
            ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS token_expiry TIMESTAMPTZ;
            ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS last_sync_time TIMESTAMPTZ;
            ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS last_ping_ms INTEGER;
            """;

        // ── Step 2: Add columns that CopyLog entity expects (critical for R2DBC SELECT * to work) ──
        String step2 = """
            ALTER TABLE copy_logs ADD COLUMN IF NOT EXISTS entry_price NUMERIC;
            ALTER TABLE copy_logs ADD COLUMN IF NOT EXISTS filled_qty INTEGER;
            ALTER TABLE copy_logs ADD COLUMN IF NOT EXISTS invested_value NUMERIC;
            """;

        // ── Step 3: PnL snapshot table ──
        String step3 = """
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
            CREATE INDEX IF NOT EXISTS idx_copy_logs_pending_entry ON copy_logs(child_status) WHERE child_status = 'PLACED' AND entry_price IS NULL;
            """;

        // Run each step independently so one failure doesn't block the rest
        databaseClient.sql(step1).then()
            .doOnSuccess(v -> log.info("Migration step 1 (core schema) completed."))
            .doOnError(e -> log.error("Migration step 1 failed: {}", e.getMessage()))
            .then(databaseClient.sql(step2).then())
            .doOnSuccess(v -> log.info("Migration step 2 (copy_logs columns) completed."))
            .doOnError(e -> log.error("Migration step 2 failed: {}", e.getMessage()))
            .then(databaseClient.sql(step3).then())
            .doOnSuccess(v -> log.info("Migration step 3 (PnL tables + indexes) completed."))
            .doOnError(e -> log.error("Migration step 3 failed: {}", e.getMessage()))
            .subscribe();
    }
}
