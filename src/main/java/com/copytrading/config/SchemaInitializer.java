package com.copytrading.config;

import io.r2dbc.spi.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

@Configuration
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    @Bean
    public ApplicationRunner initSchema(ConnectionFactory cf) {
        return args -> {
            DatabaseClient db = DatabaseClient.create(cf);

            String[] statements = {
                """
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
                  skip_reason      VARCHAR(50),
                  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS notifications (
                  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  user_id    UUID NOT NULL,
                  type       VARCHAR(50) NOT NULL,
                  title      VARCHAR(200) NOT NULL,
                  message    TEXT,
                  read       BOOLEAN NOT NULL DEFAULT FALSE,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS master_active_accounts (
                  master_id        UUID PRIMARY KEY,
                  broker_account_id UUID NOT NULL,
                  activated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS trades (
                  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS risk_rules (
                  user_id               UUID PRIMARY KEY,
                  max_trades_per_day    INTEGER NOT NULL DEFAULT 50,
                  max_open_positions    INTEGER NOT NULL DEFAULT 20,
                  max_capital_exposure  DOUBLE PRECISION NOT NULL DEFAULT 80,
                  margin_check_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
                  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS broker_error_logs (
                  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  user_id           UUID NOT NULL,
                  broker_account_id UUID,
                  broker_name       VARCHAR(30),
                  error_code        VARCHAR(50),
                  error_message     TEXT,
                  trade_id          UUID,
                  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """,
                // Add skip_reason column to existing copy_logs tables
                "ALTER TABLE copy_logs ADD COLUMN IF NOT EXISTS skip_reason VARCHAR(50)"
            };

            for (String sql : statements) {
                db.sql(sql).then().subscribe(
                    v -> {},
                    e -> log.warn("Schema init warning: {}", e.getMessage()),
                    () -> log.info("Schema check OK: {}", sql.trim().substring(0, Math.min(60, sql.trim().length())))
                );
            }
        };
    }
}
