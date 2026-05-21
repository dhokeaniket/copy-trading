package com.copytrading.engine;

/** Canonical instrument classification for copy-engine routing. */
public enum InstrumentType {
    EQUITY,
    INDEX,
    FUTURES,
    OPTION_CE,
    OPTION_PE,
    UNKNOWN;

    public boolean isDerivative() {
        return this == FUTURES || this == OPTION_CE || this == OPTION_PE;
    }

    public boolean requiresLotRounding() {
        return isDerivative();
    }
}
