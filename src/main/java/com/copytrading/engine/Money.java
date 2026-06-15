package com.copytrading.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Utility class for safe money/price arithmetic.
 * Eliminates floating-point drift in order quantities, prices, P&L, and exposure.
 *
 * Usage:
 *   Money.price("19.95")           → BigDecimal with 2-digit scale
 *   Money.scale("1.25")            → BigDecimal with 4-digit scale (scaling factor)
 *   Money.scaleQty(3, 1.25)        → 4 (rounded HALF_UP)
 *   Money.pnl(ltp, avg, qty, side) → precise P&L
 *   Money.pct(used, total)         → percentage with 2 decimals
 *   Money.parse(obj)               → safe parse from any Object (never silent 0)
 */
public final class Money {

    // Scale constants
    public static final int PRICE_SCALE = 2;      // ₹19.95
    public static final int SCALE_FACTOR = 4;     // 1.2500
    public static final int PCT_SCALE = 2;        // 80.25%
    public static final int PNL_SCALE = 2;        // ₹-1234.56

    public static final RoundingMode ROUND = RoundingMode.HALF_EVEN; // Banker's rounding for money
    public static final RoundingMode ROUND_QTY = RoundingMode.HALF_UP; // For order quantity

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(PRICE_SCALE, ROUND);
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {} // utility class

    // ── Price ──

    /** Parse a price value. Returns BigDecimal with 2 decimal places. */
    public static BigDecimal price(String value) {
        if (value == null || value.isBlank()) return ZERO;
        return new BigDecimal(value.trim()).setScale(PRICE_SCALE, ROUND);
    }

    public static BigDecimal price(double value) {
        return BigDecimal.valueOf(value).setScale(PRICE_SCALE, ROUND);
    }

    public static BigDecimal price(Object obj) {
        BigDecimal bd = parse(obj);
        return bd != null ? bd.setScale(PRICE_SCALE, ROUND) : ZERO;
    }

    // ── Scaling Factor ──

    /** Parse a scaling factor (e.g., 1.25, 0.5). 4 decimal precision. */
    public static BigDecimal scale(String value) {
        if (value == null || value.isBlank()) return BigDecimal.ONE;
        return new BigDecimal(value.trim()).setScale(SCALE_FACTOR, ROUND);
    }

    public static BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE_FACTOR, ROUND);
    }

    // ── Quantity Scaling ──

    /**
     * Scale master quantity by a factor. Returns integer qty (HALF_UP).
     * E.g., scaleQty(3, 1.25) = 4, scaleQty(1, 0.5) = 1
     */
    public static int scaleQty(int masterQty, double scalingFactor) {
        return BigDecimal.valueOf(masterQty)
                .multiply(BigDecimal.valueOf(scalingFactor))
                .setScale(0, ROUND_QTY)
                .intValueExact();
    }

    public static int scaleQty(int masterQty, BigDecimal scalingFactor) {
        return BigDecimal.valueOf(masterQty)
                .multiply(scalingFactor)
                .setScale(0, ROUND_QTY)
                .intValueExact();
    }

    // ── P&L ──

    /**
     * Calculate P&L: (ltp - avgPrice) * qty for BUY, (avgPrice - ltp) * qty for SELL.
     */
    public static BigDecimal pnl(double ltp, double avgPrice, int qty, String side) {
        BigDecimal diff = BigDecimal.valueOf(ltp).subtract(BigDecimal.valueOf(avgPrice));
        if ("SELL".equalsIgnoreCase(side)) diff = diff.negate();
        return diff.multiply(BigDecimal.valueOf(qty)).setScale(PNL_SCALE, ROUND);
    }

    public static BigDecimal pnl(BigDecimal ltp, BigDecimal avgPrice, int qty, String side) {
        BigDecimal diff = ltp.subtract(avgPrice);
        if ("SELL".equalsIgnoreCase(side)) diff = diff.negate();
        return diff.multiply(BigDecimal.valueOf(qty)).setScale(PNL_SCALE, ROUND);
    }

    // ── Percentage ──

    /**
     * Calculate percentage: (part / total) * 100, with 2 decimal precision.
     * Returns 0 if total is zero (avoids division by zero).
     */
    public static BigDecimal pct(double part, double total) {
        if (total == 0) return ZERO;
        return BigDecimal.valueOf(part)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), PCT_SCALE, ROUND);
    }

    /**
     * Margin utilization %: ((total - available) / total) * 100
     */
    public static BigDecimal marginPct(double total, double available) {
        if (total <= 0) return ZERO;
        return BigDecimal.valueOf(total - available)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), PCT_SCALE, ROUND);
    }

    // ── Safe Parsing ──

    /**
     * Parse any Object to BigDecimal. Returns null (not 0) on failure.
     * Callers must handle null explicitly — no silent zero coercion.
     */
    public static BigDecimal parse(Object obj) {
        if (obj == null) return null;
        if (obj instanceof BigDecimal bd) return bd;
        if (obj instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = obj.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse with a default value (use when 0 is a valid fallback).
     */
    public static BigDecimal parseOrZero(Object obj) {
        BigDecimal bd = parse(obj);
        return bd != null ? bd : ZERO;
    }

    /**
     * Parse to double safely. Returns defaultVal on failure.
     * Use only for interfacing with broker APIs that require double.
     */
    public static double toDouble(Object obj, double defaultVal) {
        BigDecimal bd = parse(obj);
        return bd != null ? bd.doubleValue() : defaultVal;
    }

    // ── Comparison ──

    /** Compare with tolerance for float boundary decisions (e.g., risk gates). */
    public static boolean exceeds(BigDecimal value, BigDecimal limit) {
        return value.compareTo(limit) > 0;
    }

    /** Format for display: ₹1,234.56 */
    public static String formatINR(BigDecimal amount) {
        if (amount == null) return "₹0.00";
        return "₹" + amount.setScale(PRICE_SCALE, ROUND).toPlainString();
    }
}
