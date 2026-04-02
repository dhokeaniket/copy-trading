package com.copytrading.broker.groww;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real Groww Trading API client.
 * Base URL: https://api.groww.in
 */
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

    /** Generate access token using API key + secret (TOTP flow) */
    public Mono<Map> generateToken(String apiKey, String totpCode) {
        return client.post()
                .uri("/v1/api/login/token")
                .header("Authorization", apiKey)
                .bodyValue(Map.of("key_type", "totp", "totp", totpCode))
                .retrieve()
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("GROWW_TOKEN_RESPONSE status={}", r.get("status")));
    }

    /** Generate access token using API key + secret (approval flow) */
    public Mono<Map> generateTokenWithSecret(String apiKey, String apiSecret) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String checksum = sha256Hex(apiSecret + timestamp);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("key_type", "approval");
        body.put("checksum", checksum);
        body.put("timestamp", timestamp);
        log.info("GROWW_TOKEN_REQUEST timestamp={} checksum={}", timestamp, checksum);
        return client.post()
                .uri("/v1/api/login/token")
                .header("Authorization", apiKey)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(String.class)
                                .flatMap(err -> {
                                    log.error("GROWW_TOKEN_ERROR status={} body={}", response.statusCode(), err);
                                    return Mono.error(new RuntimeException("Groww API error " + response.statusCode() + ": " + err));
                                }))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("GROWW_TOKEN_RESPONSE status={}", r.get("status")));
    }

    /** Get user margin details */
    public Mono<Map> getMargin(String accessToken) {
        return client.get()
                .uri("/v1/margins/detail/user")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(Map.class);
    }

    /** Get user positions */
    public Mono<Map> getPositions(String accessToken, String segment) {
        return client.get()
                .uri(u -> u.path("/v1/positions/user")
                        .queryParamIfPresent("segment", java.util.Optional.ofNullable(segment))
                        .build())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(Map.class);
    }

    /** Get user holdings */
    public Mono<Map> getHoldings(String accessToken) {
        return client.get()
                .uri("/v1/holdings/user")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(Map.class);
    }

    /** Place order */
    public Mono<Map> placeOrder(String accessToken, Map<String, Object> orderBody) {
        return client.post()
                .uri("/v1/order/create")
                .header("Authorization", "Bearer " + accessToken)
                .bodyValue(orderBody)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("GROWW_ORDER status={} orderId={}", r.get("status"), r.get("groww_order_id")));
    }

    /** Cancel order */
    public Mono<Map> cancelOrder(String accessToken, String growwOrderId, String segment) {
        return client.post()
                .uri("/v1/order/cancel")
                .header("Authorization", "Bearer " + accessToken)
                .bodyValue(Map.of("groww_order_id", growwOrderId, "segment", segment))
                .retrieve()
                .bodyToMono(Map.class);
    }

    /** Get order status */
    public Mono<Map> getOrderStatus(String accessToken, String growwOrderId, String segment) {
        return client.get()
                .uri(u -> u.path("/v1/order/status/{id}")
                        .queryParam("segment", segment)
                        .build(growwOrderId))
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(Map.class);
    }

    /** List orders */
    public Mono<Map> listOrders(String accessToken, String segment) {
        return client.get()
                .uri(u -> u.path("/v1/order/list")
                        .queryParamIfPresent("segment", java.util.Optional.ofNullable(segment))
                        .build())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(Map.class);
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
