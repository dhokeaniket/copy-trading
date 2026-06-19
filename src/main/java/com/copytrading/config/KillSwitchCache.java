package com.copytrading.config;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class KillSwitchCache {
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean val) {
        enabled.set(val);
    }
}
