package com.copytrading.engine;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Normalizes product codes from master brokers and maps them to child broker APIs.
 */
public final class BrokerProductMapper {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** NSE equity intraday new-order cutoff (most brokers block after ~15:20 IST). */
    private static final LocalTime INTRADAY_ORDER_END = LocalTime.of(15, 20);
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

    private BrokerProductMapper() {}

    /** Map broker-specific product codes (e.g. Upstox D/I) to MIS / CNC / NRML. */
    public static String normalizeProduct(String product) {
        if (product == null || product.isBlank()) return "MIS";
        return switch (product.trim().toUpperCase()) {
            case "D", "DELIVERY" -> "CNC";
            case "I", "INTRADAY" -> "MIS";
            case "CNC", "MIS", "NRML" -> product.trim().toUpperCase();
            default -> product.trim().toUpperCase();
        };
    }

    public static String toDhanProductType(String normalizedProduct, boolean isFnO) {
        String p = normalizedProduct != null ? normalizedProduct.trim().toUpperCase() : "MIS";
        if ("CNC".equals(p) || "D".equals(p) || "DELIVERY".equals(p)) {
            return "CNC";
        }
        if ("NRML".equals(p)) {
            return isFnO ? "MARGIN" : "CNC";
        }
        return "INTRADAY";
    }

    public static boolean isIntradayProduct(String normalizedProduct) {
        if (normalizedProduct == null || normalizedProduct.isBlank()) return false;
        String p = normalizedProduct.trim().toUpperCase();
        return "MIS".equals(p) || "I".equals(p) || "INTRADAY".equals(p);
    }

    /** Equity intraday new orders blocked before 9:15 or after 15:20 IST. */
    public static boolean isEquityIntradayOrderWindowClosed() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        LocalTime t = now.toLocalTime();
        return t.isBefore(MARKET_OPEN) || !t.isBefore(INTRADAY_ORDER_END);
    }

    public static boolean shouldSkipIntradayCopy(String normalizedProduct, boolean isFnO) {
        return isIntradayProduct(normalizedProduct) && !isFnO && isEquityIntradayOrderWindowClosed();
    }

    public static String marketClosedMessage() {
        return "Cannot place copy trade: market is closed for intraday orders (9:15 AM–3:20 PM IST).";
    }
}
