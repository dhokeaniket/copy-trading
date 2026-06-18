package com.copytrading.broker.upstox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
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
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", apiKey);
        form.add("client_secret", apiSecret);
        form.add("redirect_uri", "https://localhost");
        form.add("grant_type", "client_credentials");
        log.info("UPSTOX_SECRET_LOGIN apiKey={}...", apiKey.substring(0, Math.min(8, apiKey.length())));
        return client.post()
                .uri("/v2/login/authorization/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
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
        String redirect = redirectUri != null && !redirectUri.isBlank() ? redirectUri : "https://localhost";
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", authCode);
        form.add("client_id", apiKey);
        form.add("client_secret", apiSecret);
        form.add("redirect_uri", redirect);
        form.add("grant_type", "authorization_code");
        log.info("UPSTOX_TOKEN_REQ apiKey={} redirectLen={}", apiKey.substring(0, Math.min(8, apiKey.length())), redirect.length());
        return client.post()
                .uri("/v2/login/authorization/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
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
        log.info("UPSTOX_ORDER_REQ body={}", body);
        return client.post()
                .uri("/v2/order/place")
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> {
                            log.error("UPSTOX_ORDER_FAILED status={} body={} request={}", r.statusCode(), e, body);
                            return Mono.error(new RuntimeException("Upstox order " + r.statusCode() + ": " + e));
                        }))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("UPSTOX_ORDER_RESP: {}", r));
    }

    public Mono<Map> getOrders(String accessToken) {
        return client.get()
                .uri("/v2/order/retrieve-all")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("Upstox orders " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class);
    }

    public Mono<Map> getTrades(String accessToken) {
        return client.get()
                .uri("/v2/order/trades/get-trades-for-day")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getHoldings(String accessToken) {
        return client.get()
                .uri("/v2/portfolio/long-term-holdings")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> cancelOrder(String accessToken, String orderId) {
        return client.delete()
                .uri(u -> u.path("/v2/order/cancel").queryParam("order_id", orderId).build())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getProfile(String accessToken) {
        return client.get()
                .uri("/v2/user/profile")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().bodyToMono(Map.class);
    }
}
