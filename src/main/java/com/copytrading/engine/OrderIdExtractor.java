package com.copytrading.engine;

import java.util.Map;

/**
 * Extracts broker_order_id and exchange_order_id from each broker's order placement response.
 *
 * Each broker returns order IDs in different formats:
 *
 * Groww:     { "payload": { "order_id": "...", "exchange_order_id": "..." } }
 * Zerodha:   { "data": { "order_id": "230601000001" } } — same as exchange order id
 * Upstox:    { "data": { "order_id": "230601000001" } }
 * Fyers:     { "id": "230601000001", "message": "..." }
 * Dhan:      { "orderId": "112233445566", "orderStatus": "TRANSIT" }
 * AngelOne:  { "data": { "orderid": "230601000001", "uniqueorderid": "..." } }
 */
public final class OrderIdExtractor {

    private OrderIdExtractor() {}

    /**
     * Result holder for extracted order IDs.
     */
    public record OrderIds(String brokerOrderId, String exchangeOrderId) {
        public String display() {
            if (exchangeOrderId != null && !exchangeOrderId.equals(brokerOrderId)) {
                return brokerOrderId + " (exch:" + exchangeOrderId + ")";
            }
            return brokerOrderId != null ? brokerOrderId : "unknown";
        }
    }

    /**
     * Extract order IDs from any broker's response Map.
     */
    @SuppressWarnings("unchecked")
    public static OrderIds extract(Map response, String brokerId) {
        if (response == null) return new OrderIds("unknown", null);

        return switch (brokerId != null ? brokerId.toUpperCase() : "") {
            case "GROWW" -> extractGroww(response);
            case "ZERODHA" -> extractZerodha(response);
            case "UPSTOX" -> extractUpstox(response);
            case "FYERS" -> extractFyers(response);
            case "DHAN" -> extractDhan(response);
            case "ANGELONE" -> extractAngelOne(response);
            default -> extractGeneric(response);
        };
    }

    @SuppressWarnings("unchecked")
    private static OrderIds extractGroww(Map response) {
        // Groww: { payload: { order_id, exchange_order_id } } or flat { order_id }
        Object payload = response.get("payload");
        Map data = payload instanceof Map ? (Map) payload : response;
        String orderId = str(data, "order_id", str(data, "orderId", null));
        String exchId = str(data, "exchange_order_id", str(data, "exchangeOrderId", null));
        return new OrderIds(orderId != null ? orderId : "unknown", exchId);
    }

    @SuppressWarnings("unchecked")
    private static OrderIds extractZerodha(Map response) {
        // Zerodha: { data: { order_id: "..." } }
        Object data = response.get("data");
        if (data instanceof Map m) {
            String orderId = str(m, "order_id", null);
            return new OrderIds(orderId != null ? orderId : "unknown", orderId);
        }
        String orderId = str(response, "order_id", null);
        return new OrderIds(orderId != null ? orderId : "unknown", orderId);
    }

    @SuppressWarnings("unchecked")
    private static OrderIds extractUpstox(Map response) {
        // Upstox: { data: { order_id: "..." } }
        Object data = response.get("data");
        if (data instanceof Map m) {
            String orderId = str(m, "order_id", str(m, "orderId", null));
            String exchId = str(m, "exchange_order_id", null);
            return new OrderIds(orderId != null ? orderId : "unknown", exchId);
        }
        String orderId = str(response, "order_id", null);
        return new OrderIds(orderId != null ? orderId : "unknown", null);
    }

    private static OrderIds extractFyers(Map response) {
        // Fyers: { id: "...", message: "..." } or { data: { id: "..." } }
        String orderId = str(response, "id", str(response, "order_id", null));
        if (orderId == null) {
            Object data = response.get("data");
            if (data instanceof Map m) {
                orderId = str(m, "id", str(m, "orderNumber", null));
            }
        }
        return new OrderIds(orderId != null ? orderId : "unknown", null);
    }

    private static OrderIds extractDhan(Map response) {
        // Dhan: { orderId: "...", exchangeOrderId: "..." }
        String orderId = str(response, "orderId", str(response, "order_id", null));
        String exchId = str(response, "exchangeOrderId", str(response, "exchange_order_id", null));
        return new OrderIds(orderId != null ? orderId : "unknown", exchId);
    }

    @SuppressWarnings("unchecked")
    private static OrderIds extractAngelOne(Map response) {
        // AngelOne: { data: { orderid: "...", uniqueorderid: "..." } }
        Object data = response.get("data");
        if (data instanceof Map m) {
            String orderId = str(m, "orderid", str(m, "orderId", null));
            String uniqueId = str(m, "uniqueorderid", null);
            return new OrderIds(orderId != null ? orderId : "unknown", uniqueId);
        }
        String orderId = str(response, "orderid", str(response, "orderId", null));
        return new OrderIds(orderId != null ? orderId : "unknown", null);
    }

    private static OrderIds extractGeneric(Map response) {
        for (String key : new String[]{"order_id", "orderId", "orderid", "id"}) {
            String val = str(response, key, null);
            if (val != null) return new OrderIds(val, null);
        }
        // Try nested data
        Object data = response.get("data");
        if (data instanceof Map m) {
            for (String key : new String[]{"order_id", "orderId", "orderid"}) {
                String val = str(m, key, null);
                if (val != null) return new OrderIds(val, null);
            }
        }
        return new OrderIds("unknown", null);
    }

    private static String str(Map map, String key, String defaultVal) {
        if (map == null) return defaultVal;
        Object v = map.get(key);
        if (v == null) return defaultVal;
        String s = v.toString().trim();
        return s.isEmpty() ? defaultVal : s;
    }
}
