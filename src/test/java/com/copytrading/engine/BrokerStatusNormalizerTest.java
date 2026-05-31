package com.copytrading.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies every broker's raw status/side maps to the canonical value documented in BROKER-FIELD-MAPPING.md.
 */
class BrokerStatusNormalizerTest {

    // ---- Terminal fills ----
    @Test
    void toCanonical_complete() {
        assertEquals("COMPLETE", BrokerStatusNormalizer.toCanonical("COMPLETE", "ZERODHA"));
        assertEquals("COMPLETE", BrokerStatusNormalizer.toCanonical("complete", "UPSTOX"));
        assertEquals("COMPLETE", BrokerStatusNormalizer.toCanonical("TRADED", "DHAN"));
        assertEquals("COMPLETE", BrokerStatusNormalizer.toCanonical("COMPLETED", "GROWW"));
        assertEquals("COMPLETE", BrokerStatusNormalizer.toCanonical("EXECUTED", "GROWW"));
        assertEquals("COMPLETE", BrokerStatusNormalizer.toCanonical("2", "FYERS"));
        assertEquals("COMPLETE", BrokerStatusNormalizer.toCanonical("complete", "ANGELONE"));
    }

    @Test
    void toCanonical_cancelled() {
        assertEquals("CANCELLED", BrokerStatusNormalizer.toCanonical("CANCELLED", "ZERODHA"));
        assertEquals("CANCELLED", BrokerStatusNormalizer.toCanonical("cancelled", "UPSTOX"));
        assertEquals("CANCELLED", BrokerStatusNormalizer.toCanonical("cancelled after market order", "UPSTOX"));
        assertEquals("CANCELLED", BrokerStatusNormalizer.toCanonical("CANCELLED", "DHAN"));
        assertEquals("CANCELLED", BrokerStatusNormalizer.toCanonical("1", "FYERS"));
        assertEquals("CANCELLED", BrokerStatusNormalizer.toCanonical("cancelled", "ANGELONE"));
    }

    @Test
    void toCanonical_rejectedExpiredFailed() {
        assertEquals("REJECTED", BrokerStatusNormalizer.toCanonical("REJECTED", "ZERODHA"));
        assertEquals("REJECTED", BrokerStatusNormalizer.toCanonical("5", "FYERS"));
        assertEquals("EXPIRED", BrokerStatusNormalizer.toCanonical("EXPIRED", "DHAN"));
        assertEquals("EXPIRED", BrokerStatusNormalizer.toCanonical("7", "FYERS"));
        assertEquals("FAILED", BrokerStatusNormalizer.toCanonical("FAILED", "GROWW"));
    }

    // ---- Non-terminal ----
    @Test
    void toCanonical_openAndPending() {
        assertEquals("OPEN", BrokerStatusNormalizer.toCanonical("OPEN", "ZERODHA"));
        assertEquals("OPEN", BrokerStatusNormalizer.toCanonical("PENDING", "DHAN"));
        assertEquals("OPEN", BrokerStatusNormalizer.toCanonical("6", "FYERS"));
        assertEquals("PART_TRADED", BrokerStatusNormalizer.toCanonical("PART_TRADED", "DHAN"));
        assertEquals("TRANSIT", BrokerStatusNormalizer.toCanonical("TRANSIT", "DHAN"));
        assertEquals("TRANSIT", BrokerStatusNormalizer.toCanonical("4", "FYERS"));
        assertEquals("TRANSIT", BrokerStatusNormalizer.toCanonical("put order req received", "ZERODHA"));
        assertEquals("TRANSIT", BrokerStatusNormalizer.toCanonical("APPROVED", "GROWW"));
        assertEquals("TRIGGER_PENDING", BrokerStatusNormalizer.toCanonical("trigger pending", "ANGELONE"));
        assertEquals("TRIGGER_PENDING", BrokerStatusNormalizer.toCanonical("TRIGGER_PENDING", "GROWW"));
    }

    // ---- Side ----
    @Test
    void toCanonicalSide() {
        assertEquals("BUY", BrokerStatusNormalizer.toCanonicalSide("1", "FYERS"));
        assertEquals("SELL", BrokerStatusNormalizer.toCanonicalSide("-1", "FYERS"));
        assertEquals("BUY", BrokerStatusNormalizer.toCanonicalSide("BUY", "ZERODHA"));
        assertEquals("SELL", BrokerStatusNormalizer.toCanonicalSide("sell", "UPSTOX"));
        assertEquals("BUY", BrokerStatusNormalizer.toCanonicalSide("B", "DHAN"));
        assertEquals("SELL", BrokerStatusNormalizer.toCanonicalSide("S", "GROWW"));
    }

    // ---- Helpers ----
    @Test
    void terminalAndFill() {
        assertTrue(BrokerStatusNormalizer.isTerminal("COMPLETE"));
        assertTrue(BrokerStatusNormalizer.isTerminal("CANCELLED"));
        assertFalse(BrokerStatusNormalizer.isTerminal("OPEN"));
        assertTrue(BrokerStatusNormalizer.isFill("COMPLETE"));
        assertFalse(BrokerStatusNormalizer.isFill("CANCELLED"));
    }

    // ---- Partial fill guard (OrderNormalizer) ----
    @Test
    void partialFillNotFullyFilled() {
        assertFalse(OrderNormalizer.isFullyFilled(50, 100)); // partial -> not a close
        assertTrue(OrderNormalizer.isFullyFilled(100, 100));
        assertTrue(OrderNormalizer.isFullyFilled(75, 0));    // unknown order qty -> treat positive fill as full
        assertFalse(OrderNormalizer.isFullyFilled(0, 100));
    }
}
