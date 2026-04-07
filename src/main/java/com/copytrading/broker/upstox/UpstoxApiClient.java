package com.copytrading.broker.upstox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class UpstoxApiClient {

    private static final Logger log = LoggerFactory.getLogger(UpstoxApiClient.class);
    private static final String BASE = "https://api.upstox.com";
    private final WebClient client;

    public UpstoxApiClient(WebClient.Builder builder) {
        this.client = builder.baseUrl(BASE)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * Direct login using api_key + api_secret (client_credentials grant).
     */
    public Mono<Map> generateTokenWithSecret(String apiKey, String apiSecret) {
        String body = "client_id=" + apiKey
                + "&client_secret=" + apiSecret
                + "&redirect_uri=https://localhost"
                + "&grant_type=client_credentials";
        log.info("UPSTOX_SECRET_LOGIN apiKey={}...", apiKey.substring(0, Math.min(8, apiKey.length())));
        return client.post()
                .uri("/v2/login/authorization/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Upstox secret login " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("UPSTOX_SECRET_RESP keys={}", r.keySet()));
    }

    /**
     * Exchange auth_code for access_token via OAuth.
     */
    public Mono<Map> generateToken(String apiKey, String apiSecret, String authCode, String redirectUri) {
        String body = "code=" + authCode
                + "&client_id=" + apiKey
                + "&client_secret=" + apiSecret
                + "&redirect_uri=" + (redirectUri != null ? redirectUri : "https://localhost")
                + "&grant_type=authorization_code";
        log.info("UPSTOX_TOKEN_REQ apiKey={}...", apiKey.substring(0, Math.min(8, apiKey.length())));
        return client.post()
                .uri("/v2/login/authorization/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Upstox " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("UPSTOX_TOKEN_RESP keys={}", r.keySet()));
    }

    public Mono<Map> getFundsMargin(String accessToken) {
        return client.get()
                .uri("/v2/user/get-funds-and-margin")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Upstox margin " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("UPSTOX_MARGIN_RESP keys={}", r.keySet()));
    }

    public Mono<Map> getPositions(String accessToken) {
        return client.get()
                .uri("/v2/portfolio/short-term-positions")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> placeOrder(String accessToken, Map<String, Object> body) {
        return client.post()
                .uri("/v2/order/place")
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> cancelOrder(String accessToken, String orderId) {
        return client.delete()
                .uri(u -> u.path("/v2/order/cancel").queryParam("order_id", orderId).build())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().bodyToMono(Map.class);
    }
}
