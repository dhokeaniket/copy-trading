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
                """
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
