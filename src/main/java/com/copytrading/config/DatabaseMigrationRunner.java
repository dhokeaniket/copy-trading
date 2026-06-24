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
        
        String sql = """
            ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS is_copy_enable BOOLEAN NOT NULL DEFAULT TRUE;
            
            ALTER TABLE trades ADD COLUMN IF NOT EXISTS realized_pnl DOUBLE PRECISION DEFAULT 0.0;
            ALTER TABLE copy_logs ADD COLUMN IF NOT EXISTS realized_pnl DOUBLE PRECISION DEFAULT 0.0;
            
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
            
            ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS token_expiry TIMESTAMPTZ;
            ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS last_sync_time TIMESTAMPTZ;
            ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS last_ping_ms INTEGER;

            -- Calculate mock realized_pnl for older completed trades (Demo logic)
            UPDATE trades SET realized_pnl = round((price * quantity * 0.05)::numeric, 2) WHERE action = 'SELL' AND status IN ('COMPLETED', 'SUCCESS') AND realized_pnl = 0.0;
            UPDATE trades SET realized_pnl = round((price * quantity * -0.02)::numeric, 2) WHERE action = 'BUY' AND status IN ('COMPLETED', 'SUCCESS') AND realized_pnl = 0.0;

            -- Mock data for broker status missing values (Demo logic)
            UPDATE broker_accounts SET 
                last_ping_ms = floor(random() * 50 + 20)::int,
                last_sync_time = now() - (random() * interval '10 minutes'),
                token_expiry = now() + (random() * interval '8 hours')
            WHERE last_ping_ms IS NULL;
            """;
        
        databaseClient.sql(sql)
                .then()
                .doOnSuccess(v -> log.info("Successfully ensured 'is_copy_enable' column exists in 'broker_accounts'."))
                .doOnError(e -> log.error("Failed to apply database migration: {}", e.getMessage()))
                .subscribe();
    }
}
