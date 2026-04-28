package com.copytrading.broker.angelone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Angel One SmartAPI v2 REST client.
 *
 * Base URL: https://apiconnect.angelone.in
 * Auth: Bearer jwtToken + api_key header
 *
 * Login: POST /rest/auth/angelbroking/user/v1/loginByPassword
 *   Body: { clientcode, password, totp }
 *   Response: { data: { jwtToken, refreshToken, feedToken } }
 *
 * Place Order: POST /rest/secure/angelbroking/order/v1/placeOrder
 *   Body: { variety, tradingsymbol, symboltoken, transactiontype, exchange,
 *           ordertype, producttype, duration, price, squareoff, stoploss, quantity, triggerprice }
 *   Response: { data: { orderid, uniqueorderid } }
 */
@Component
public class AngelOneApiClient {

    private static final Logger log = LoggerFactory.getLogger(AngelOneApiClient.class);
    private static final String BASE = "https://apiconnect.angelone.in";
    private final WebClient client;

    public AngelOneApiClient(WebClient.Builder builder) {
        this.client = builder.baseUrl(BASE)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * Login via clientcode + password + TOTP.
     * POST /rest/auth/angelbroking/user/v1/loginByPassword
     */
    public Mono<Map> login(String apiKey, String clientCode, String password, String totp) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("clientcode", clientCode);
        body.put("password", password);
        body.put("totp", totp);
        log.info("ANGELONE_LOGIN clientCode={}", clientCode);
        return client.post()
                .uri("/rest/auth/angelbroking/user/v1/loginByPassword")
                .header("X-PrivateKey", apiKey)
                .header("X-ClientLocalIP", "127.0.0.1")
                .header("X-ClientPublicIP", "127.0.0.1")
                .header("X-MACAddress", "00:00:00:00:00:00")
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("AngelOne login " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("ANGELONE_LOGIN_RESP keys={}", r.keySet()));
    }

    /**
     * Generate token (refresh).
     * POST /rest/auth/angelbroking/jwt/v1/generateTokens
     */
    public Mono<Map> generateToken(String apiKey, String jwtToken, String refreshToken) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("refreshToken", refreshToken);
        return client.post()
                .uri("/rest/auth/angelbroking/jwt/v1/generateTokens")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-PrivateKey", apiKey)
                .header("X-ClientLocalIP", "127.0.0.1")
                .header("X-ClientPublicIP", "127.0.0.1")
                .header("X-MACAddress", "00:00:00:00:00:00")
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("AngelOne token " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class);
    }

    /**
     * Place order.
     * POST /rest/secure/angelbroking/order/v1/placeOrder
     */
    public Mono<Map> placeOrder(String apiKey, String jwtToken, Map<String, Object> body) {
        log.info("ANGELONE_PLACE_ORDER body={}", body);
        return client.post()
                .uri("/rest/secure/angelbroking/order/v1/placeOrder")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-PrivateKey", apiKey)
                .header("X-ClientLocalIP", "127.0.0.1")
                .header("X-ClientPublicIP", "127.0.0.1")
                .header("X-MACAddress", "00:00:00:00:00:00")
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("AngelOne placeOrder " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class)
                .doOnNext(r -> log.info("ANGELONE_ORDER_RESP keys={}", r.keySet()));
    }

    /**
     * Cancel order.
     * POST /rest/secure/angelbroking/order/v1/cancelOrder
     */
    public Mono<Map> cancelOrder(String apiKey, String jwtToken, String variety, String orderId) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("variety", variety);
        body.put("orderid", orderId);
        return client.post()
                .uri("/rest/secure/angelbroking/order/v1/cancelOrder")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-PrivateKey", apiKey)
                .header("X-ClientLocalIP", "127.0.0.1")
                .header("X-ClientPublicIP", "127.0.0.1")
                .header("X-MACAddress", "00:00:00:00:00:00")
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("AngelOne cancel " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class);
    }

    /**
     * Get order book.
     * GET /rest/secure/angelbroking/order/v1/getOrderBook
     */
    public Mono<Map> getOrders(String apiKey, String jwtToken) {
        return client.get()
                .uri("/rest/secure/angelbroking/order/v1/getOrderBook")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-PrivateKey", apiKey)
                .header("X-ClientLocalIP", "127.0.0.1")
                .header("X-ClientPublicIP", "127.0.0.1")
                .header("X-MACAddress", "00:00:00:00:00:00")
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("AngelOne orders " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class);
    }

    /**
     * Get trade book.
     * GET /rest/secure/angelbroking/order/v1/getTradeBook
     */
    public Mono<Map> getTrades(String apiKey, String jwtToken) {
        return client.get()
                .uri("/rest/secure/angelbroking/order/v1/getTradeBook")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-PrivateKey", apiKey)
                .header("X-ClientLocalIP", "127.0.0.1")
                .header("X-ClientPublicIP", "127.0.0.1")
                .header("X-MACAddress", "00:00:00:00:00:00")
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("AngelOne trades " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class);
    }

    /**
     * Get positions.
     * GET /rest/secure/angelbroking/order/v1/getPosition
     */
    public Mono<Map> getPositions(String apiKey, String jwtToken) {
        return client.get()
                .uri("/rest/secure/angelbroking/order/v1/getPosition")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-PrivateKey", apiKey)
                .header("X-ClientLocalIP", "127.0.0.1")
                .header("X-ClientPublicIP", "127.0.0.1")
                .header("X-MACAddress", "00:00:00:00:00:00")
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("AngelOne positions " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class);
    }

    /**
     * Get holdings.
     * GET /rest/secure/angelbroking/portfolio/v1/getHolding
     */
    public Mono<Map> getHoldings(String apiKey, String jwtToken) {
        return client.get()
                .uri("/rest/secure/angelbroking/portfolio/v1/getHolding")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-PrivateKey", apiKey)
                .header("X-ClientLocalIP", "127.0.0.1")
                .header("X-ClientPublicIP", "127.0.0.1")
                .header("X-MACAddress", "00:00:00:00:00:00")
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("AngelOne holdings " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class);
    }

    /**
     * Get funds/margin (RMS limits).
     * GET /rest/secure/angelbroking/user/v1/getRMS
     */
    public Mono<Map> getRMS(String apiKey, String jwtToken) {
        return client.get()
                .uri("/rest/secure/angelbroking/user/v1/getRMS")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-PrivateKey", apiKey)
                .header("X-ClientLocalIP", "127.0.0.1")
                .header("X-ClientPublicIP", "127.0.0.1")
                .header("X-MACAddress", "00:00:00:00:00:00")
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("AngelOne RMS " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class);
    }

    /**
     * Get user profile.
     * GET /rest/secure/angelbroking/user/v1/getProfile
     */
    public Mono<Map> getProfile(String apiKey, String jwtToken) {
        return client.get()
                .uri("/rest/secure/angelbroking/user/v1/getProfile")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-PrivateKey", apiKey)
                .header("X-ClientLocalIP", "127.0.0.1")
                .header("X-ClientPublicIP", "127.0.0.1")
                .header("X-MACAddress", "00:00:00:00:00:00")
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .retrieve()
                .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                        .flatMap(e -> Mono.error(new RuntimeException("AngelOne profile " + r.statusCode() + ": " + e))))
                .bodyToMono(Map.class);
    }

    /**
     * Logout.
     * POST /rest/secure/angelbroking/user/v1/logout
     */
    public Mono<Map> logout(String apiKey, String jwtToken, String clientCode) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("clientcode", clientCode);
        return client.post()
                .uri("/rest/secure/angelbroking/user/v1/logout")
                .header("Authorization", "Bearer " + jwtToken)
                .header("X-PrivateKey", apiKey)
                .header("X-ClientLocalIP", "127.0.0.1")
                .header("X-ClientPublicIP", "127.0.0.1")
                .header("X-MACAddress", "00:00:00:00:00:00")
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class);
    }
}
