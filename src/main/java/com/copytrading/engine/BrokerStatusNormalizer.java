package com.copytrading.engine;

import java.util.Set;

/**
 * Inbound status normalizer: any broker's raw order status/side -> canonical value.
 *
 * <p>Single source of truth for status mapping, built from {@code BROKER-FIELD-MAPPING.md}.
 * Once normalized on ingestion ({@link CanonicalOrderMapper#fromBrokerOrder}), the rest of the engine
 * reasons only about canonical values and never about raw broker strings.
 *
 * <p>Canonical statuses:
 * {@code COMPLETE, CANCELLED, REJECTED, EXPIRED, FAILED, CLOSED} (terminal) and
 * {@code OPEN, PART_TRADED, TRIGGER_PENDING, TRANSIT, MODIFIED, MODIFY_PENDING, CANCEL_PENDING,
 * AMO_PENDING, DELIVERY_AWAITED, TRIGGERED} (non-terminal).
 */
public final class BrokerStatusNormalizer {

    private BrokerStatusNormalizer() {}

    public static final String COMPLETE = "COMPLETE";
    public static final String CANCELLED = "CANCELLED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String FAILED = "FAILED";
    public static final String CLOSED = "CLOSED";
    public static final String OPEN = "OPEN";
    public static final String PART_TRADED = "PART_TRADED";

    private static final Set<String> TERMINAL = Set.of(COMPLETE, CANCELLED, REJECTED, EXPIRED, FAILED, CLOSED);

    /** Returns canonical status from any broker's raw value. Null in -> null out. */
    public static String toCanonical(String rawStatus, String broker) {
        if (rawStatus == null) return null;
        String s = rawStatus.trim();
        if (s.isEmpty()) return null;
        String b = broker == null ? "" : broker.trim().toUpperCase();

        // FYERS is the only broker with integer status codes.
        if ("FYERS".equals(b)) {
            switch (s) {
                case "1": return CANCELLED;
                case "2": return COMPLETE;
                case "4": return "TRANSIT";
                case "5": return REJECTED;
                case "6": return OPEN;
                case "7": return EXPIRED;
                default: /* fall through to string handling */ break;
            }
        }

        String key = s.toUpperCase().replaceAll("[\\s\\-]+", "_").replaceAll("_+", "_");
        return switch (key) {
            case "COMPLETE", "COMPLETED", "TRADED", "EXECUTED" -> COMPLETE;
            case "CANCELLED", "CANCELED", "CANCELLED_AMO", "CANCELLED_AFTER_MARKET_ORDER" -> CANCELLED;
            case "REJECTED" -> REJECTED;
            case "EXPIRED" -> EXPIRED;
            case "FAILED" -> FAILED;
            case "CLOSED" -> CLOSED;
            case "OPEN", "PENDING" -> OPEN;
            case "PART_TRADED", "PARTIALLY_FILLED", "PART_FILLED", "PARTIAL" -> PART_TRADED;
            case "TRIGGER_PENDING" -> "TRIGGER_PENDING";
            case "TRIGGERED" -> "TRIGGERED";
            case "TRANSIT", "NEW", "ACKED", "APPROVED",
                 "PUT_ORDER_REQ_RECEIVED", "VALIDATION_PENDING", "OPEN_PENDING" -> "TRANSIT";
            case "MODIFIED" -> "MODIFIED";
            case "NOT_MODIFIED", "NOT_CANCELLED" -> OPEN; // still live at exchange
            case "MODIFY_PENDING", "MODIFY_VALIDATION_PENDING", "MODIFICATION_REQUESTED" -> "MODIFY_PENDING";
            case "CANCEL_PENDING", "CANCELLATION_REQUESTED" -> "CANCEL_PENDING";
            case "AMO_REQ_RECEIVED", "AFTER_MARKET_ORDER_REQ_RECEIVED",
                 "MODIFY_AFTER_MARKET_ORDER_REQ_RECEIVED" -> "AMO_PENDING";
            case "DELIVERY_AWAITED" -> "DELIVERY_AWAITED";
            default -> key;
        };
    }

    /** Returns canonical side (BUY/SELL) from any broker's raw value. */
    public static String toCanonicalSide(String rawSide, String broker) {
        if (rawSide == null) return null;
        String s = rawSide.trim().toUpperCase();
        if (s.isEmpty()) return null;
        if ("1".equals(s) || "B".equals(s) || "BUY".equals(s)) return "BUY";
        if ("-1".equals(s) || "S".equals(s) || "SELL".equals(s)) return "SELL";
        return s;
    }

    /** True for canonical terminal statuses. */
    public static boolean isTerminal(String canonicalStatus) {
        return canonicalStatus != null && TERMINAL.contains(canonicalStatus.trim().toUpperCase());
    }

    /** True when the canonical status is a (potentially) successful fill. */
    public static boolean isFill(String canonicalStatus) {
        if (canonicalStatus == null) return false;
        String s = canonicalStatus.trim().toUpperCase();
        return COMPLETE.equals(s) || CLOSED.equals(s);
    }
}
