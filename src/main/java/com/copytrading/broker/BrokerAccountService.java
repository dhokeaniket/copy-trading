package com.copytrading.broker;

import com.copytrading.broker.dto.*;
import com.copytrading.broker.groww.GrowwApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

@Service
public class BrokerAccountService {

    private static final Logger log = LoggerFactory.getLogger(BrokerAccountService.class);
    private static final Set<String> SUPPORTED = Set.of("GROWW", "ZERODHA", "ANGELONE", "UPSTOX", "DHAN");

    private final BrokerAccountRepository repo;
    private final GrowwApiClient growwClient;

    public BrokerAccountService(BrokerAccountRepository repo, GrowwApiClient growwClient) {
        this.repo = repo;
        this.growwClient = growwClient;
    }

    // 3.1 List supported brokers
    public Mono<Map<String, Object>> listBrokers() {
        List<Map<String, Object>> brokers = List.of(
            brokerInfo("GROWW", "Groww", true, List.of("apiKey", "apiSecret", "clientId")),
            brokerInfo("ZERODHA", "Zerodha", false, List.of("apiKey", "apiSecret", "clientId")),
            brokerInfo("ANGELONE", "Angel One", false, List.of("apiKey", "apiSecret", "clientId")),
            brokerInfo("UPSTOX", "Upstox", false, List.of("apiKey", "apiSecret", "clientId")),
            brokerInfo("DHAN", "Dhan", false, List.of("apiKey", "apiSecret", "clientId"))
        );
        return Mono.just(Map.of("brokers", brokers));
    }

    // 3.2 Link account
    public Mono<Map<String, Object>> linkAccount(UUID userId, LinkAccountRequest req) {
        String broker = req.getBrokerId() != null ? req.getBrokerId().toUpperCase() : "";
        if (!SUPPORTED.contains(broker)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported broker: " + broker));
        }
        BrokerAccount a = new BrokerAccount();
        a.setUserId(userId);
        a.setBrokerId(broker);
        a.setClientId(req.getClientId());
        a.setApiKey(req.getApiKey());
        a.setApiSecret(req.getApiSecret());
        a.setAccessToken(req.getAccessToken());
        a.setNickname(req.getAccountNickname());
        a.setStatus(req.getAccessToken() != null ? "LINKED" : "AUTH_REQUIRED");
        a.setSessionActive(false);
        a.setLinkedAt(Instant.now());
        return repo.save(a).map(saved -> {
            log.info("BROKER_LINKED id={} user={} broker={}", saved.getId(), userId, broker);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("accountId", saved.getId());
            r.put("brokerId", saved.getBrokerId());
            r.put("status", saved.getStatus());
            return r;
        });
    }

    // 3.3 List user's accounts
    public Mono<Map<String, Object>> listAccounts(UUID userId) {
        return repo.findByUserId(userId)
                .map(BrokerAccountDto::from)
                .collectList()
                .map(list -> Map.<String, Object>of("accounts", list));
    }

    // 3.4 Get account detail
    public Mono<BrokerAccountDto> getAccount(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .map(BrokerAccountDto::from)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")));
    }

    // 3.5 Update account
    public Mono<Map<String, String>> updateAccount(UUID accountId, UUID userId, UpdateAccountRequest req) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .flatMap(a -> {
                    if (req.getApiKey() != null) a.setApiKey(req.getApiKey());
                    if (req.getApiSecret() != null) a.setApiSecret(req.getApiSecret());
                    if (req.getAccountNickname() != null) a.setNickname(req.getAccountNickname());
                    return repo.save(a).thenReturn(Map.of("message", "Account updated"));
                });
    }

    // 3.6 Delete account
    public Mono<Map<String, String>> deleteAccount(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .flatMap(a -> repo.delete(a).thenReturn(Map.of("message", "Account unlinked")));
    }

    // 3.7 Login to broker (create session)
    public Mono<Map<String, Object>> loginToBroker(UUID accountId, UUID userId, BrokerLoginRequest req) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .flatMap(a -> {
                    if ("GROWW".equals(a.getBrokerId())) {
                        return loginGroww(a, req);
                    }
                    // Mock login for other brokers
                    a.setSessionActive(true);
                    a.setStatus("ACTIVE");
                    a.setSessionExpires(Instant.now().plusSeconds(86400));
                    return repo.save(a).map(s -> mockSessionResponse(s));
                });
    }

    private Mono<Map<String, Object>> loginGroww(BrokerAccount a, BrokerLoginRequest req) {
        Mono<Map> tokenMono;
        if (req != null && req.getTotpCode() != null && !req.getTotpCode().isBlank()) {
            tokenMono = growwClient.generateToken(a.getApiKey(), req.getTotpCode());
        } else {
            tokenMono = growwClient.generateTokenWithSecret(a.getApiKey(), a.getApiSecret());
        }
        return tokenMono
                .flatMap(resp -> {
                    log.info("GROWW_LOGIN_RESPONSE raw={}", resp);
                    // Groww returns token directly or inside payload
                    String token = null;
                    if (resp.containsKey("payload") && resp.get("payload") instanceof Map p) {
                        token = (String) p.get("token");
                    } else if (resp.containsKey("token")) {
                        token = (String) resp.get("token");
                    }
                    if (token == null || token.isBlank()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                "Groww login failed: " + resp.getOrDefault("error", resp)));
                    }
                    a.setAccessToken(token);
                    a.setSessionActive(true);
                    a.setStatus("ACTIVE");
                    a.setSessionExpires(Instant.now().plusSeconds(86400));
                    return repo.save(a).map(s -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("status", "SESSION_ACTIVE");
                        r.put("expiresAt", s.getSessionExpires().toString());
                        return r;
                    });
                })
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    log.error("GROWW_LOGIN_FAILED error={}", e.getMessage(), e);
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Groww API error: " + e.getMessage()));
                });
    }

    // 3.8 Check session status
    public Mono<Map<String, Object>> getSessionStatus(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .map(a -> {
                    boolean active = a.isSessionActive() && a.getSessionExpires() != null
                            && a.getSessionExpires().isAfter(Instant.now());
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("sessionActive", active);
                    r.put("expiresAt", a.getSessionExpires() != null ? a.getSessionExpires().toString() : null);
                    return r;
                });
    }

    // 3.9 Get margin (real for Groww, mock for others)
    public Mono<Map<String, Object>> getMargin(UUID accountId, UUID userId) {
        return getActiveAccount(accountId, userId).flatMap(a -> {
            if ("GROWW".equals(a.getBrokerId())) {
                return growwClient.getMargin(a.getAccessToken())
                        .map(resp -> {
                            Object payload = resp.get("payload");
                            if (payload instanceof Map p) {
                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put("availableMargin", p.getOrDefault("clear_cash", 0));
                                r.put("usedMargin", p.getOrDefault("net_margin_used", 0));
                                r.put("totalFunds", ((Number) p.getOrDefault("clear_cash", 0)).doubleValue()
                                        + ((Number) p.getOrDefault("net_margin_used", 0)).doubleValue());
                                r.put("collateral", p.getOrDefault("collateral_available", 0));
                                return r;
                            }
                            return Map.<String, Object>of("raw", resp);
                        });
            }
            return Mono.just(mockMargin());
        });
    }

    // 3.10 Get positions (real for Groww, mock for others)
    public Mono<Map<String, Object>> getPositions(UUID accountId, UUID userId) {
        return getActiveAccount(accountId, userId).flatMap(a -> {
            if ("GROWW".equals(a.getBrokerId())) {
                return growwClient.getPositions(a.getAccessToken(), null)
                        .map(resp -> {
                            Object payload = resp.get("payload");
                            return Map.<String, Object>of("positions", payload != null ? payload : List.of());
                        });
            }
            return Mono.just(Map.<String, Object>of("positions", List.of()));
        });
    }

    // Admin: 3.11 List all accounts
    public Mono<Map<String, Object>> adminListAccounts(UUID userId, String brokerId) {
        var flux = userId != null ? repo.findByUserId(userId)
                : brokerId != null ? repo.findByBrokerId(brokerId.toUpperCase())
                : repo.findAll();
        return flux.map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", a.getId());
            m.put("userId", a.getUserId());
            m.put("brokerId", a.getBrokerId());
            m.put("clientId", a.getClientId());
            m.put("status", a.getStatus());
            return m;
        }).collectList().map(list -> Map.<String, Object>of("accounts", list));
    }

    // Admin: 3.12 Broker health status
    public Mono<Map<String, Object>> adminBrokerStatus() {
        List<Map<String, Object>> statuses = List.of(
            Map.of("brokerId", "GROWW", "name", "Groww", "apiStatus", "UP", "latencyMs", 45, "lastChecked", Instant.now().toString()),
            Map.of("brokerId", "ZERODHA", "name", "Zerodha", "apiStatus", "MOCK", "latencyMs", 0, "lastChecked", Instant.now().toString()),
            Map.of("brokerId", "ANGELONE", "name", "Angel One", "apiStatus", "MOCK", "latencyMs", 0, "lastChecked", Instant.now().toString()),
            Map.of("brokerId", "UPSTOX", "name", "Upstox", "apiStatus", "MOCK", "latencyMs", 0, "lastChecked", Instant.now().toString()),
            Map.of("brokerId", "DHAN", "name", "Dhan", "apiStatus", "MOCK", "latencyMs", 0, "lastChecked", Instant.now().toString())
        );
        return Mono.just(Map.of("brokers", statuses));
    }

    // Helpers
    private Mono<BrokerAccount> getActiveAccount(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active broker session. Login first.")));
    }

    private Map<String, Object> mockSessionResponse(BrokerAccount a) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "SESSION_ACTIVE");
        r.put("expiresAt", a.getSessionExpires().toString());
        return r;
    }

    private Map<String, Object> mockMargin() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("availableMargin", 100000);
        r.put("usedMargin", 25000);
        r.put("totalFunds", 125000);
        r.put("collateral", 0);
        return r;
    }

    private Map<String, Object> brokerInfo(String id, String name, boolean active, List<String> fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("brokerId", id);
        m.put("name", name);
        m.put("requiredFields", fields);
        m.put("isActive", active);
        return m;
    }
}
