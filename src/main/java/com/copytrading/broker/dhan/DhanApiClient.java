package com.copytrading.broker.dhan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class DhanApiClient {

    private static final Logger log = LoggerFactory.getLogger(DhanApiClient.class);
    private final WebClient authClient;
    private final WebClient apiClient;

    public DhanApiClient(WebClient.Builder builder) {
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
                    // Dhan returns an array, wrap it in a map
                    Map<String, Object> result = new java.util.LinkedHashMap<>();
                    result.put("raw", body);
                    return (Map) result;
                });
    }

    public Mono<Map> placeOrder(String accessToken, Map<String, Object> body) {
        return apiClient.post()
                .uri("/v2/orders")
                .header("access-token", accessToken)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve().bodyToMono(Map.class);
    }

    public Mono<Map> getProfile(String accessToken) {
        return apiClient.get()
                .uri("/v2/profile")
                .header("access-token", accessToken)
                .retrieve().bodyToMono(Map.class);
    }
}
