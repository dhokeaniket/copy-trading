package com.copytrading.broker.dhan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DhanApiClient {

    private static final Logger log = LoggerFactory.getLogger(DhanApiClient.class);
    private final WebClient authClient;
    private final WebClient apiClient;
    private final ObjectMapper objectMapper;

    public DhanApiClient(WebClient.Builder builder, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.authClient = builder.clone().baseUrl("https://auth.dhan.co")
                .defaultHeader("Accept", "application/json")
                .build();
        this.apiClient = builder.clone().baseUrl("https://api.dhan.co")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /** Step 1: Generate consent */
    public Mono<Map> generateConsent(String apiKey, String apiSecret, String clientId) {
        log.info("DHAN_CONSENT_REQ clientId={}", clientId);
        return authClient.post()
                .uri(u -> u.path("/app/generate-consent").queryParam("client_id", clientId).build())
                .header("app_id", apiKey)
                .header("app_secret", apiSecret)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Dhan consent " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("DHAN_CONSENT_RESP keys={}", r.keySet()));
    }

    /** Step 3: Consume consent (exchange tokenId for access token) */
    public Mono<Map> consumeConsent(String apiKey, String apiSecret, String tokenId) {
        log.info("DHAN_CONSUME_REQ tokenId={}...", tokenId.substring(0, Math.min(8, tokenId.length())));
        return authClient.get()
                .uri(u -> u.path("/app/consumeApp-consent").queryParam("tokenId", tokenId).build())
                .header("app_id", apiKey)
                .header("app_secret", apiSecret)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Dhan consume " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("DHAN_CONSUME_RESP keys={}", r.keySet()));
    }

    public Mono<Map> getFunds(String accessToken) {
        return apiClient.get()
                .uri("/v2/fundlimit")
                .header("access-token", accessToken)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Dhan funds " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class);
    }

    public Mono<Map> getPositions(String accessToken) {
        return apiClient.get()
                .uri("/v2/positions")
                .header("access-token", accessToken)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Dhan positions " + r.statusCode() + ": " + e))))
                .bodyToMono(String.class)
                .map(body -> {
                    List<Map<String, Object>> positions = DhanResponseParser.parseListPayload(body, objectMapper);
                    log.debug("DHAN_POSITIONS count={}", positions.size());
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("positions", positions);
                    return (Map) result;
                });
    }

    public Mono<Map> placeOrder(String accessToken, Map<String, Object> body) {
        log.info("DHAN_ORDER_REQ body={}", body);
        return apiClient.post()
                .uri("/v2/orders")
                .header("access-token", accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> {
                            log.error("DHAN_ORDER_FAILED status={} body={} request={}", r.statusCode(), e, body);
                            return Mono.error(new RuntimeException("Dhan order " + r.statusCode() + ": " + e));
                        }))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("DHAN_ORDER_RESP: {}", r));
    }

    /** Search for securityId by symbol name using Dhan's search API */
    public Mono<String> searchSecurityId(String accessToken, String symbol, String exchange) {
        return apiClient.get()
                .uri("/v2/search?q=" + symbol)
                .header("access-token", accessToken)
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> {
                    if (body.contains("securityId")) {
                        try {
                            int idx = body.indexOf("\"securityId\"");
                            if (idx > 0) {
                                String sub = body.substring(idx + 14, Math.min(idx + 30, body.length()));
                                StringBuilder num = new StringBuilder();
                                for (char c : sub.toCharArray()) {
                                    if (Character.isDigit(c)) num.append(c);
                                    else if (num.length() > 0) break;
                                }
                                if (num.length() > 0) return num.toString();
                            }
                        } catch (Exception e) { /* parse failed */ }
                    }
                    return "";
                })
                .onErrorReturn("");
    }

    public Mono<Map> getOrders(String accessToken) {
        return apiClient.get()
                .uri("/v2/orders")
                .header("access-token", accessToken)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Dhan orders " + r.statusCode() + ": " + e))))
                .bodyToMono(String.class)
                .map(body -> {
                    List<Map<String, Object>> orders = DhanResponseParser.parseListPayload(body, objectMapper);
                    log.debug("DHAN_ORDERS count={}", orders.size());
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("orders", orders);
                    return (Map) result;
                })
                .onErrorResume(e -> Mono.just(Map.of("orders", List.of(), "error", e.getMessage())));
    }

    public Mono<Map> getTrades(String accessToken) {
        return apiClient.get()
                .uri("/v2/trades")
                .header("access-token", accessToken)
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("trades", DhanResponseParser.parseListPayload(body, objectMapper));
                    return (Map) result;
                })
                .onErrorResume(e -> Mono.just(Map.of("trades", List.of(), "error", e.getMessage())));
    }

    public Mono<Map> getHoldings(String accessToken) {
        return apiClient.get()
                .uri("/v2/holdings")
                .header("access-token", accessToken)
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("holdings", DhanResponseParser.parseListPayload(body, objectMapper));
                    return (Map) result;
                })
                .onErrorResume(e -> Mono.just(Map.of("holdings", List.of(), "error", e.getMessage())));
    }

    public Mono<Map> cancelOrder(String accessToken, String orderId) {
        return apiClient.delete()
                .uri("/v2/orders/" + orderId)
                .header("access-token", accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getProfile(String accessToken) {
        return apiClient.get()
                .uri("/v2/profile")
                .header("access-token", accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getOrderDetails(String accessToken, String orderId) {
        return apiClient.get()
                .uri("/v2/orders/" + orderId)
                .header("access-token", accessToken)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Dhan getOrder " + r.statusCode() + ": " + e))))
                .bodyToMono(String.class)
                .map(body -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    try {
                        map = objectMapper.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>(){});
                    } catch (Exception e) {}
                    return map;
                });
    }
}
