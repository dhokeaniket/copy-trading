package com.copytrading.engine;

import java.util.Map;

/**
 * Canonical extraction and normalization for broker order maps (polling / postbacks).
 */
public final class OrderNormalizer {

    private OrderNormalizer() {}

    public static String extractField(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object val = map.get(key);
            if (val != null) {
                String s = val.toString().trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    public static String extractOrderId(Map<String, Object> order) {
        return extractField(order, "order_id", "orderId", "id", "groww_order_id", "orderid");
    }

    public static String extractStatus(Map<String, Object> order) {
        String status = extractField(order, "status", "order_status", "orderStatus", "order_status_name");
        if (status != null) return status;
        Object code = order != null ? order.get("status") : null;
        if (code instanceof Number n) {
            int s = n.intValue();
            if (s == 2) return "COMPLETE";
            if (s == 1) return "CANCELLED";
        }
        return null;
    }

    public static String extractSymbol(Map<String, Object> order) {
        return extractField(order, "tradingsymbol", "trading_symbol", "symbol", "tradingSymbol");
    }

    public static String extractSide(Map<String, Object> order) {
        String side = extractField(order, "transaction_type", "transactiontype", "side", "transactionType");
        return normalizeSide(side);
    }

    public static String normalizeSide(String side) {
        if (side == null) return null;
        String s = side.trim().toUpperCase();
        if ("1".equals(s) || "B".equals(s)) return "BUY";
        if ("-1".equals(s) || "S".equals(s)) return "SELL";
        if ("BUY".equals(s) || "SELL".equals(s)) return s;
        return side;
    }

    public static int extractFilledQty(Map<String, Object> order) {
        String qtyStr = extractField(order,
                "filled_quantity", "filledQuantity", "filled_qty", "filledQty",
                "traded_quantity", "tradedQuantity", "traded_qty",
                "quantity", "qty");
        if (qtyStr == null) return 0;
        try {
            return Math.max(0, (int) Double.parseDouble(qtyStr));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Total quantity placed on the order (used to detect partial fills vs full fills). */
    public static int extractOrderQty(Map<String, Object> order) {
        String qtyStr = extractField(order,
                "quantity", "qty", "order_quantity", "orderQty", "orderQuantity");
        if (qtyStr == null) return 0;
        try {
            return Math.max(0, (int) Double.parseDouble(qtyStr));
        } catch (Exception e) {
            return 0;
        }
    }

    public static double extractPrice(Map<String, Object> order) {
        String priceStr = extractField(order, "average_fill_price", "averagePrice", "average_price", "price");
        if (priceStr == null) return 0;
        try {
            return Double.parseDouble(priceStr);
        } catch (Exception e) {
            return 0;
        }
    }

    public static double extractTriggerPrice(Map<String, Object> order) {
        String t = extractField(order, "trigger_price", "triggerPrice", "stop_price", "stopPrice");
        if (t == null) return 0;
        try {
            return Double.parseDouble(t);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Terminal fill — safe to copy once. */
    public static boolean isFilledOrderStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.trim().toUpperCase();
        return "COMPLETE".equals(s) || "COMPLETED".equals(s) || "EXECUTED".equals(s) || "TRADED".equals(s)
                || "2".equals(s);
    }

    /** Partial fill — keep polling; do not mark order as known yet. */
    public static boolean isPartialFillStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.trim().toUpperCase();
        return s.contains("PARTIAL") || "PARTIALLY_FILLED".equals(s) || "PART_FILLED".equals(s);
    }

    /**
     * A fill is "full" only when the filled quantity reaches the ordered quantity.
     * Some brokers report a terminal status (EXECUTED/COMPLETE/TRADED/2) while still partially filled —
     * those must keep polling and must not be actioned as a close.
     * When orderQty is unknown (0), we cannot detect a partial, so any positive fill is treated as full.
     */
    public static boolean isFullyFilled(int filledQty, int orderQty) {
        if (filledQty <= 0) return false;
        if (orderQty <= 0) return true;
        return filledQty >= orderQty;
    }

    /** Backward-compatible: treat as full when ordered quantity is unknown. */
    public static boolean shouldProcessForCopy(String status, int filledQty) {
        return shouldProcessForCopy(status, filledQty, 0);
    }

    public static boolean shouldProcessForCopy(String status, int filledQty, int orderQty) {
        if (isPartialFillStatus(status)) return false;
        if (isFilledOrderStatus(status)) return isFullyFilled(filledQty, orderQty);
        return false;
    }
}
