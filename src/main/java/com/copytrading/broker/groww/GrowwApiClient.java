package com.copytrading.broker.groww;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GrowwApiClient {

    private static final Logger log = LoggerFactory.getLogger(GrowwApiClient.class);
    private static final String BASE = "https://api.groww.in";
    private final WebClient client;

    public GrowwApiClient(WebClient.Builder builder) {
        this.client = builder.baseUrl(BASE)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("X-API-VERSION", "1.0")
                .build();
    }

    public Mono<Map> generateToken(String apiKey, String totpCode) {
        return client.post()
                .uri("/v1/token/api/access")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("key_type", "totp", "totp", totpCode))
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Groww " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class);
    }

    public Mono<Map> generateTokenWithSecret(String apiKey, String apiSecret) {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String checksum = sha256Hex(apiSecret + ts);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("key_type", "approval");
        body.put("checksum", checksum);
        body.put("timestamp", ts);
        log.info("GROWW_TOKEN_REQ ts={}", ts);
        return client.post()
                .uri("/v1/token/api/access")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Groww " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("GROWW_TOKEN_RESP keys={}", r.keySet()));
    }

    public Mono<Map> getMargin(String accessToken) {
        return client.get().uri("/v1/margins/detail/user")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getPositions(String accessToken, String segment) {
        return client.get()
                .uri(u -> u.path("/v1/positions/user")
                        .queryParamIfPresent("segment", java.util.Optional.ofNullable(segment)).build())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getHoldings(String accessToken) {
        return client.get().uri("/v1/holdings/user")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> placeOrder(String accessToken, Map<String, Object> body) {
        return client.post().uri("/v1/order/create")
                .header("Authorization", "Bearer " + accessToken)
                .bodyValue(body).retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> cancelOrder(String accessToken, String orderId, String segment) {
        return client.post().uri("/v1/order/cancel")
                .header("Authorization", "Bearer " + accessToken)
                .bodyValue(Map.of("groww_order_id", orderId, "segment", segment))
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getOrderStatus(String accessToken, String orderId, String segment) {
        return client.get()
                .uri(u -> u.path("/v1/order/status/{id}").queryParam("segment", segment).build(orderId))
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> listOrders(String accessToken, String segment) {
        return client.get()
                .uri(u -> u.path("/v1/order/list")
                        .queryParamIfPresent("segment", java.util.Optional.ofNullable(segment)).build())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
