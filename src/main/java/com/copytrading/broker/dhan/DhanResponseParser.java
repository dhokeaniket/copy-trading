package com.copytrading.broker.dhan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parses Dhan v2 JSON array responses (positions, orders, trades). */
public final class DhanResponseParser {

    private static final Logger log = LoggerFactory.getLogger(DhanResponseParser.class);

    private DhanResponseParser() {}

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseListPayload(String body, ObjectMapper objectMapper) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String trimmed = body.trim();
        try {
            if (trimmed.startsWith("[")) {
                List<Map<String, Object>> list = objectMapper.readValue(
                        body, new TypeReference<List<Map<String, Object>>>() {});
                return list != null ? list : List.of();
            }
            if (trimmed.startsWith("{")) {
                Map<String, Object> wrapper = objectMapper.readValue(
                        body, new TypeReference<Map<String, Object>>() {});
                for (String key : List.of("data", "orders", "orderBook", "positions", "trades", "holdings")) {
                    Object v = wrapper.get(key);
                    if (v instanceof List<?> list) {
                        return toMapList(list);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("DHAN_JSON_PARSE_FAIL len={} err={}", body.length(), e.getMessage());
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toMapList(List<?> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }
}
