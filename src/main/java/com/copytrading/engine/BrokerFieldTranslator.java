package com.copytrading.engine;

/**
 * Outbound field translator: canonical order values -> broker-specific placement fields.
 *
 * <p>Single source of truth for how each broker names and encodes order-placement fields.
 * Built directly from {@code BROKER-FIELD-MAPPING.md}. When a broker changes its API, update that
 * doc and the matching branch here — order placement code must never hardcode these values inline.
 *
 * <p>Inputs are canonical:
 * <ul>
 *   <li>side: {@code BUY} / {@code SELL}</li>
 *   <li>orderType: {@code MARKET} / {@code LIMIT} / {@code SL} / {@code SL-M}</li>
 *   <li>product: {@code MIS} / {@code CNC} / {@code NRML} (also accepts broker aliases like D/I)</li>
 *   <li>exchange: {@code NSE} / {@code BSE} / {@code MCX}</li>
 * </ul>
 */
public final class BrokerFieldTranslator {

    private BrokerFieldTranslator() {}

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    private static String broker(String brokerId) {
        return brokerId == null ? "" : brokerId.trim().toUpperCase();
    }

    private static boolean isMarket(String orderType) {
        return "MARKET".equals(norm(orderType));
    }

    private static boolean isLimit(String orderType) {
        return "LIMIT".equals(norm(orderType));
    }

    private static boolean isSlMarket(String orderType) {
        return "SL-M".equals(norm(orderType)) || "SLM".equals(norm(orderType)) || "SL_M".equals(norm(orderType));
    }

    private static boolean isSlLimit(String orderType) {
        return "SL".equals(norm(orderType));
    }

    /** Canonical BUY/SELL -> broker transaction-type value (FYERS uses integer 1/-1). */
    public static String transactionType(String side, String brokerId) {
        boolean buy = "BUY".equals(norm(side)) || "1".equals(norm(side)) || "B".equals(norm(side));
        if ("FYERS".equals(broker(brokerId))) {
            return buy ? "1" : "-1";
        }
        return buy ? "BUY" : "SELL";
    }

    /** Canonical MARKET/LIMIT/SL/SL-M -> broker order-type value. */
    public static String orderType(String orderType, String brokerId) {
        boolean market = isMarket(orderType);
        boolean limit = isLimit(orderType);
        boolean slm = isSlMarket(orderType);
        boolean sl = isSlLimit(orderType);
        // Default unknown to MARKET-equivalent behaviour for safety.
        return switch (broker(brokerId)) {
            case "ZERODHA", "UPSTOX" -> slm ? "SL-M" : sl ? "SL" : limit ? "LIMIT" : "MARKET";
            case "GROWW" -> slm ? "SL_M" : sl ? "SL" : limit ? "LIMIT" : "MARKET";
            case "DHAN" -> slm ? "STOP_LOSS_MARKET" : sl ? "STOP_LOSS" : limit ? "LIMIT" : "MARKET";
            case "ANGELONE" -> slm ? "STOPLOSS_MARKET" : sl ? "STOPLOSS_LIMIT" : limit ? "LIMIT" : "MARKET";
            case "FYERS" -> slm ? "3" : sl ? "4" : limit ? "1" : "2";
            default -> market ? "MARKET" : limit ? "LIMIT" : slm ? "SL-M" : "SL";
        };
    }

    /**
     * Canonical product -> broker product value.
     * {@code isFnO} matters for carry-forward (NRML) on brokers that distinguish it from delivery.
     */
    public static String product(String product, String brokerId, boolean isFnO) {
        String p = BrokerProductMapper.normalizeProduct(product); // -> MIS / CNC / NRML
        boolean mis = "MIS".equals(p);
        boolean cnc = "CNC".equals(p);
        boolean nrml = "NRML".equals(p);
        return switch (broker(brokerId)) {
            case "ZERODHA" -> mis ? "MIS" : cnc ? "CNC" : "NRML";
            case "GROWW" -> {
                // Groww F&O carry uses NRML; CNC is not valid for F&O.
                if (isFnO && cnc) yield "NRML";
                yield mis ? "MIS" : cnc ? "CNC" : "NRML";
            }
            case "UPSTOX" -> mis ? "I" : "D"; // CNC and NRML(F&O carry) both map to D
            case "DHAN" -> {
                if (cnc) yield "CNC";
                if (nrml) yield isFnO ? "MARGIN" : "CNC";
                yield "INTRADAY";
            }
            case "FYERS" -> mis ? "INTRADAY" : cnc ? "CNC" : "MARGIN";
            case "ANGELONE" -> mis ? "INTRADAY" : cnc ? "DELIVERY" : "CARRYFORWARD";
            default -> p;
        };
    }

    /**
     * Canonical exchange (NSE/BSE/MCX) -> broker exchange/exchangeSegment value.
     * For F&O, NSE -> NFO/NSE_FNO and BSE -> BFO/BSE_FNO depending on broker.
     */
    public static String exchangeSegment(String exchange, String brokerId, boolean isFnO) {
        String e = norm(exchange);
        boolean bse = "BSE".equals(e);
        boolean mcx = "MCX".equals(e);
        return switch (broker(brokerId)) {
            case "ZERODHA", "UPSTOX", "ANGELONE" -> {
                if (mcx) yield "MCX";
                if (isFnO) yield bse ? "BFO" : "NFO";
                yield bse ? "BSE" : "NSE";
            }
            case "DHAN" -> {
                if (mcx) yield "MCX_COMM";
                if (isFnO) yield bse ? "BSE_FNO" : "NSE_FNO";
                yield bse ? "BSE_EQ" : "NSE_EQ";
            }
            case "GROWW", "FYERS" -> {
                // Groww and FYERS use the plain exchange for both equity and F&O.
                if (mcx) yield "MCX";
                yield bse ? "BSE" : "NSE";
            }
            default -> bse ? "BSE" : "NSE";
        };
    }

    /**
     * Broker-specific JSON field name for order validity.
     * FYERS v3 uses {@code validity} (verified against the official fyers-apiv3 SDK and /api/v3/orders/sync),
     * Angel One uses {@code duration}, everyone else uses {@code validity}.
     */
    public static String validityFieldName(String brokerId) {
        return switch (broker(brokerId)) {
            case "ANGELONE" -> "duration";
            default -> "validity";
        };
    }

    /** Validity value — all supported brokers use DAY for copied orders. */
    public static String validityValue(String brokerId) {
        return "DAY";
    }
}
