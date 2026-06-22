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
        
        // Add is_copy_enable column if it doesn't exist
        String sql = "ALTER TABLE broker_accounts ADD COLUMN IF NOT EXISTS is_copy_enable BOOLEAN NOT NULL DEFAULT TRUE;";
        
        databaseClient.sql(sql)
                .then()
                .doOnSuccess(v -> log.info("Successfully ensured 'is_copy_enable' column exists in 'broker_accounts'."))
                .doOnError(e -> log.error("Failed to apply database migration: {}", e.getMessage()))
                .subscribe();
    }
}
