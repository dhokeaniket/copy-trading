package com.copytrading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Master order polling interval for copy-trade detection.
 * {@code fixedDelay}: next poll starts after the previous cycle completes.
 */
@Component
@ConfigurationProperties(prefix = "engine.polling")
public class EnginePollingProperties {

    /** Delay between poll cycles in milliseconds (default 1000ms). */
    private long intervalMs = 1000;

    /** Wait after startup before first poll (ms). */
    private long initialDelayMs = 15_000;

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public double getIntervalSeconds() {
        return intervalMs / 1000.0;
    }
}
