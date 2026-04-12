package com.copytrading.broker.zerodha;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Component
public class ZerodhaApiClient {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaApiClient.class);
    private static final String BASE = "https://api.kite.trade";
    private final WebClient client;

    public ZerodhaApiClient(WebClient.Builder builder) {
        this.client = builder.baseUrl(BASE)
                .defaultHeader("X-Kite-Version", "3")
                .build();
    }

    /**
     * Direct login using api_key + api_secret (generates a session token using checksum).
     * checksum = SHA-256(api_key + api_secret)
     */
    public Mono<Map> generateSessionWithSecret(String apiKey, String apiSecret) {
        String checksum = sha256Hex(apiKey + apiSecret);
        log.info("ZERODHA_SECRET_LOGIN apiKey={}...", apiKey.substring(0, Math.min(6, apiKey.length())));
        return client.post()
                .uri("/session/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue("api_key=" + apiKey + "&checksum=" + checksum)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Zerodha secret login " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("ZERODHA_SECRET_RESP keys={}", r.keySet()));
    }

    /**
     * Exchange request_token for access_token.
     * checksum = SHA-256(api_key + request_token + api_secret)
     */
    public Mono<Map> generateSession(String apiKey, String apiSecret, String requestToken) {
        String checksum = sha256Hex(apiKey + requestToken + apiSecret);
        log.info("ZERODHA_SESSION_REQ apiKey={}... requestToken={}...", apiKey.substring(0, 6), requestToken.substring(0, Math.min(6, requestToken.length())));
        return client.post()
                .uri("/session/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue("api_key=" + apiKey + "&request_token=" + requestToken + "&checksum=" + checksum)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Zerodha " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("ZERODHA_SESSION_RESP keys={}", r.keySet()));
    }

    public Mono<Map> getMargins(String apiKey, String accessToken) {
        return client.get()
                .uri("/user/margins")
                .header("Authorization", "token " + apiKey + ":" + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getPositions(String apiKey, String accessToken) {
        return client.get()
                .uri("/portfolio/positions")
                .header("Authorization", "token " + apiKey + ":" + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> placeOrder(String apiKey, String accessToken, Map<String, Object> body) {
        StringBuilder form = new StringBuilder();
        body.forEach((k, v) -> {
            if (form.length() > 0) form.append("&");
            form.append(k).append("=").append(v);
        });
        return client.post()
                .uri("/orders/regular")
                .header("Authorization", "token " + apiKey + ":" + accessToken)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue(form.toString())
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> cancelOrder(String apiKey, String accessToken, String orderId) {
        return client.delete()
                .uri("/orders/regular/" + orderId)
                .header("Authorization", "token " + apiKey + ":" + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getOrders(String apiKey, String accessToken) {
        return client.get()
                .uri("/orders")
                .header("Authorization", "token " + apiKey + ":" + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getTrades(String apiKey, String accessToken) {
        return client.get()
                .uri("/trades")
                .header("Authorization", "token " + apiKey + ":" + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getHoldings(String apiKey, String accessToken) {
        return client.get()
                .uri("/portfolio/holdings")
                .header("Authorization", "token " + apiKey + ":" + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getProfile(String apiKey, String accessToken) {
        return client.get()
                .uri("/user/profile")
                .header("Authorization", "token " + apiKey + ":" + accessToken)
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
