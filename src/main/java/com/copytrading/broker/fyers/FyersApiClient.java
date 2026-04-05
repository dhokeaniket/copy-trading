package com.copytrading.broker.fyers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Component
public class FyersApiClient {

    private static final Logger log = LoggerFactory.getLogger(FyersApiClient.class);
    private static final String BASE = "https://api-t1.fyers.in/api/v3";
    private final WebClient client;

    public FyersApiClient(WebClient.Builder builder) {
        this.client = builder.baseUrl(BASE)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * Direct login using app_id + secret_key (no auth_code needed).
     * appIdHash = SHA-256(app_id + ":" + secret_key)
     */
    public Mono<Map> generateTokenWithSecret(String appId, String secretKey) {
        String appIdHash = sha256Hex(appId + ":" + secretKey);
        log.info("FYERS_SECRET_LOGIN appId={}", appId);
        return client.post()
                .uri("/validate-authcode")
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "grant_type", "client_credentials",
                        "appIdHash", appIdHash,
                        "code", appIdHash
                ))
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Fyers secret login " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("FYERS_SECRET_RESP keys={}", r.keySet()));
    }

    /**
     * Exchange auth_code for access_token.
     * appIdHash = SHA-256(app_id + ":" + secret_key)
     */
    public Mono<Map> generateToken(String appId, String secretKey, String authCode) {
        String appIdHash = sha256Hex(appId + ":" + secretKey);
        log.info("FYERS_TOKEN_REQ appId={}", appId);
        return client.post()
                .uri("/validate-authcode")
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "grant_type", "authorization_code",
                        "appIdHash", appIdHash,
                        "code", authCode
                ))
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Fyers " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("FYERS_TOKEN_RESP keys={}", r.keySet()));
    }

    public Mono<Map> getFunds(String accessToken) {
        return client.get()
                .uri("/funds")
                .header("Authorization", accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getPositions(String accessToken) {
        return client.get()
                .uri("/positions")
                .header("Authorization", accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> placeOrder(String accessToken, Map<String, Object> body) {
        return client.post()
                .uri("/orders/sync")
                .header("Authorization", accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> cancelOrder(String accessToken, String orderId) {
        return client.delete()
                .uri(u -> u.path("/orders/" + orderId).build())
                .header("Authorization", accessToken)
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
