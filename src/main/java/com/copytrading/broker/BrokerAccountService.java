package com.copytrading.broker;

import com.copytrading.broker.dhan.DhanApiClient;
import com.copytrading.broker.dto.*;
import com.copytrading.broker.fyers.FyersApiClient;
import com.copytrading.broker.groww.GrowwApiClient;
import com.copytrading.broker.upstox.UpstoxApiClient;
import com.copytrading.broker.zerodha.ZerodhaApiClient;
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
    private static final Set<String> SUPPORTED = Set.of("GROWW", "ZERODHA", "ANGELONE", "UPSTOX", "DHAN", "FYERS");

    private final BrokerAccountRepository repo;
    private final GrowwApiClient growwClient;
    private final ZerodhaApiClient zerodhaClient;
    private final FyersApiClient fyersClient;
    private final UpstoxApiClient upstoxClient;
    private final DhanApiClient dhanClient;
    private final PlatformBrokerConfig platformConfig;

    public BrokerAccountService(BrokerAccountRepository repo, GrowwApiClient growwClient,
                                ZerodhaApiClient zerodhaClient, FyersApiClient fyersClient,
                                UpstoxApiClient upstoxClient, DhanApiClient dhanClient,
                                PlatformBrokerConfig platformConfig) {
        this.repo = repo;
        this.growwClient = growwClient;
        this.zerodhaClient = zerodhaClient;
        this.fyersClient = fyersClient;
        this.upstoxClient = upstoxClient;
        this.dhanClient = dhanClient;
        this.platformConfig = platformConfig;
    }

    // 3.1 List supported brokers
    public Mono<Map<String, Object>> listBrokers() {
        List<Map<String, Object>> brokers = List.of(
            growwBrokerInfo(),
            brokerInfo("ZERODHA", "Zerodha", true, List.of(), "oauth", "requestToken"),
            brokerInfo("FYERS", "Fyers", true, List.of(), "oauth", "authCode"),
            brokerInfo("UPSTOX", "Upstox", true, List.of(), "oauth", "authCode"),
            brokerInfo("ANGELONE", "Angel One", false, List.of(), "oauth", "authCode"),
            dhanBrokerInfo()
        );
        return Mono.just(Map.of("brokers", brokers));
    }

    private Map<String, Object> dhanBrokerInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("brokerId", "DHAN");
        m.put("name", "Dhan");
        m.put("isActive", true);
        m.put("loginMethod", "oauth");
        m.put("loginField", "tokenId");
        m.put("loginOptions", List.of(
            Map.of("method", "accessToken", "description", "Paste access token from Dhan Web (Profile → DhanHQ Trading APIs)", "requiredFields", List.of("accessToken")),
            Map.of("method", "oauth", "description", "Login via Dhan (platform handles everything, user just clicks Connect)", "requiredFields", List.of())
        ));
        return m;
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
        a.setClientId(req.getClientId() != null ? req.getClientId() : "");
        a.setNickname(req.getAccountNickname());
        a.setSessionActive(false);
        a.setLinkedAt(Instant.now());

        // For OAuth brokers, use platform-level keys; for Groww, use per-user keys
        PlatformBrokerConfig.BrokerCreds platformCreds = platformConfig.getFor(broker);
        if (platformCreds != null && platformCreds.getApiKey() != null) {
            a.setApiKey(platformCreds.getApiKey());
            a.setApiSecret(platformCreds.getApiSecret());
        } else {
            a.setApiKey(req.getApiKey() != null ? req.getApiKey() : "");
            a.setApiSecret(req.getApiSecret() != null ? req.getApiSecret() : "");
        }

        a.setAccessToken(req.getAccessToken());
        if (req.getAccessToken() != null) {
            a.setStatus("ACTIVE");
            a.setSessionActive(true);
            a.setSessionExpires(Instant.now().plusSeconds(86400));
        } else {
            a.setStatus("AUTH_REQUIRED");
        }
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
                    switch (a.getBrokerId()) {
                        case "GROWW":    return loginGroww(a, req);
                        case "ZERODHA":  return loginZerodha(a, req);
                        case "FYERS":    return loginFyers(a, req);
                        case "UPSTOX":   return loginUpstox(a, req);
                        case "DHAN":     return loginDhan(a, req);
                        default:
                            // Mock login for unsupported brokers
                            a.setSessionActive(true);
                            a.setStatus("ACTIVE");
                            a.setAccessToken("mock-session-token");
                            a.setSessionExpires(Instant.now().plusSeconds(86400));
                            return repo.save(a).map(this::mockSessionResponse);
                    }
                });
    }

    // --- GROWW LOGIN ---
    private Mono<Map<String, Object>> loginGroww(BrokerAccount a, BrokerLoginRequest req) {
        Mono<Map> tokenMono;
        if (req != null && req.getTotpCode() != null && !req.getTotpCode().isBlank()) {
            tokenMono = growwClient.generateToken(a.getApiKey(), req.getTotpCode());
        } else {
            tokenMono = growwClient.generateTokenWithSecret(a.getApiKey(), a.getApiSecret());
        }
        return tokenMono.flatMap(resp -> extractAndSaveSession(a, resp, "Groww",
                r -> {
                    if (r.containsKey("payload") && r.get("payload") instanceof Map p) return (String) p.get("token");
                    return (String) r.get("token");
                }))
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    log.error("GROWW_LOGIN_FAILED error={}", e.getMessage(), e);
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Groww API error: " + e.getMessage()));
                });
    }

    // --- ZERODHA LOGIN ---
    private Mono<Map<String, Object>> loginZerodha(BrokerAccount a, BrokerLoginRequest req) {
        var creds = platformConfig.getZerodha();
        String apiKey = creds.getApiKey();
        String apiSecret = creds.getApiSecret();
        if (req == null || req.getRequestToken() == null || req.getRequestToken().isBlank()) {
            String loginUrl = "https://kite.zerodha.com/connect/login?v=3&api_key=" + apiKey;
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "requestToken required. Open this URL to login: " + loginUrl));
        }
        return zerodhaClient.generateSession(apiKey, apiSecret, req.getRequestToken())
                .flatMap(resp -> extractAndSaveSession(a, resp, "Zerodha",
                        r -> {
                            if (r.containsKey("data") && r.get("data") instanceof Map d) return (String) d.get("access_token");
                            return (String) r.get("access_token");
                        }))
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    log.error("ZERODHA_LOGIN_FAILED error={}", e.getMessage(), e);
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Zerodha API error: " + e.getMessage()));
                });
    }

    // --- FYERS LOGIN ---
    private Mono<Map<String, Object>> loginFyers(BrokerAccount a, BrokerLoginRequest req) {
        var creds = platformConfig.getFyers();
        if (req == null || req.getAuthCode() == null || req.getAuthCode().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "authCode required. Complete Fyers OAuth flow with app_id=" + creds.getApiKey()));
        }
        return fyersClient.generateToken(creds.getApiKey(), creds.getApiSecret(), req.getAuthCode())
                .flatMap(resp -> extractAndSaveSession(a, resp, "Fyers",
                        r -> (String) r.get("access_token")))
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    log.error("FYERS_LOGIN_FAILED error={}", e.getMessage(), e);
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Fyers API error: " + e.getMessage()));
                });
    }

    // --- UPSTOX LOGIN ---
    private Mono<Map<String, Object>> loginUpstox(BrokerAccount a, BrokerLoginRequest req) {
        var creds = platformConfig.getUpstox();
        if (req == null || req.getAuthCode() == null || req.getAuthCode().isBlank()) {
            String loginUrl = "https://api.upstox.com/v2/login/authorization/dialog?response_type=code&client_id=" + creds.getApiKey() + "&redirect_uri=" + platformConfig.getCallbackUrl();
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "authCode required. Open this URL to login: " + loginUrl));
        }
        return upstoxClient.generateToken(creds.getApiKey(), creds.getApiSecret(), req.getAuthCode(), platformConfig.getCallbackUrl())
                .flatMap(resp -> extractAndSaveSession(a, resp, "Upstox",
                        r -> (String) r.get("access_token")))
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    log.error("UPSTOX_LOGIN_FAILED error={}", e.getMessage(), e);
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upstox API error: " + e.getMessage()));
                });
    }

    // --- DHAN LOGIN (3-step OAuth: consent → browser login → consume) ---
    private Mono<Map<String, Object>> loginDhan(BrokerAccount a, BrokerLoginRequest req) {
        var creds = platformConfig.getDhan();
        // If tokenId provided (from browser callback), exchange for access token
        if (req != null && req.getAuthCode() != null && !req.getAuthCode().isBlank()) {
            return dhanClient.consumeConsent(creds.getApiKey(), creds.getApiSecret(), req.getAuthCode())
                    .flatMap(resp -> extractAndSaveSession(a, resp, "Dhan",
                            r -> (String) r.get("accessToken")))
                    .onErrorResume(e -> {
                        if (e instanceof ResponseStatusException) return Mono.error(e);
                        log.error("DHAN_LOGIN_FAILED error={}", e.getMessage(), e);
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Dhan API error: " + e.getMessage()));
                    });
        }
        // No tokenId — generate consent and return the login URL
        return dhanClient.generateConsent(creds.getApiKey(), creds.getApiSecret(), a.getClientId())
                .map(resp -> {
                    String consentAppId = (String) resp.get("consentAppId");
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("status", "CONSENT_GENERATED");
                    r.put("loginUrl", "https://auth.dhan.co/login/consentApp-login?consentAppId=" + consentAppId);
                    r.put("message", "Open loginUrl in browser. After login, Dhan redirects with tokenId. Call login again with {\"authCode\": \"tokenId\"}");
                    return r;
                })
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    log.error("DHAN_CONSENT_FAILED error={}", e.getMessage(), e);
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Dhan API error: " + e.getMessage()));
                });
    }

    /** Common helper: extract token from response, save session, return status */
    private Mono<Map<String, Object>> extractAndSaveSession(BrokerAccount a, Map resp, String brokerName,
                                                             java.util.function.Function<Map, String> tokenExtractor) {
        log.info("{}_LOGIN_RESPONSE raw={}", brokerName.toUpperCase(), resp);
        String token = null;
        try { token = tokenExtractor.apply(resp); } catch (Exception e) {
            log.error("{}_TOKEN_EXTRACT_FAILED: {}", brokerName.toUpperCase(), e.getMessage());
        }
        if (token == null || token.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    brokerName + " login failed: " + resp));
        }
        a.setAccessToken(token);
        a.setSessionActive(true);
        a.setStatus("ACTIVE");
        a.setSessionExpires(Instant.now().plusSeconds(86400));
        return repo.save(a).map(s -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "SESSION_ACTIVE");
            r.put("broker", brokerName);
            r.put("expiresAt", s.getSessionExpires().toString());
            return r;
        });
    }

    // 3.7b Get OAuth URL for browser-based login
    public Mono<Map<String, Object>> getOAuthUrl(UUID accountId, UUID userId, String redirectUri) {
        String redirect = (redirectUri != null && !redirectUri.isBlank()) ? redirectUri : platformConfig.getCallbackUrl();
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .map(a -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("broker", a.getBrokerId());
                    switch (a.getBrokerId()) {
                        case "GROWW":
                            r.put("loginMethod", "secret");
                            r.put("message", "No OAuth needed. Call login with empty body {}");
                            break;
                        case "ZERODHA":
                            r.put("loginMethod", "oauth");
                            r.put("loginField", "requestToken");
                            r.put("oauthUrl", "https://kite.zerodha.com/connect/login?v=3&api_key=" + platformConfig.getZerodha().getApiKey());
                            r.put("message", "Open oauthUrl in browser. After login, the callback will receive the requestToken automatically.");
                            break;
                        case "FYERS":
                            r.put("loginMethod", "oauth");
                            r.put("loginField", "authCode");
                            r.put("oauthUrl", "https://api-t1.fyers.in/api/v3/generate-authcode?client_id=" + platformConfig.getFyers().getApiKey() + "&redirect_uri=" + redirect + "&response_type=code&state=ok");
                            r.put("message", "Open oauthUrl in browser. After login, the callback will receive the authCode automatically.");
                            break;
                        case "UPSTOX":
                            r.put("loginMethod", "oauth");
                            r.put("loginField", "authCode");
                            r.put("oauthUrl", "https://api.upstox.com/v2/login/authorization/dialog?response_type=code&client_id=" + platformConfig.getUpstox().getApiKey() + "&redirect_uri=" + redirect);
                            r.put("message", "Open oauthUrl in browser. After login, the callback will receive the authCode automatically.");
                            break;
                        case "DHAN":
                            r.put("loginMethod", "oauth");
                            r.put("loginField", "tokenId");
                            r.put("message", "Call POST /login with empty body first to get loginUrl. Open it in browser. After login, Dhan redirects with tokenId. Call login again with {\"authCode\": \"tokenId\"}");
                            break;
                        default:
                            r.put("loginMethod", "mock");
                            r.put("message", "Mock broker. Call login with empty body {}");
                    }
                    return r;
                });
    }

    // 3.8 Check session status (enhanced with connectionHealth, margin, brokerName)
    public Mono<Map<String, Object>> getSessionStatus(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .map(a -> {
                    boolean active = a.isSessionActive() && a.getSessionExpires() != null
                            && a.getSessionExpires().isAfter(Instant.now());
                    String health = active ? "good" : (a.getAccessToken() != null ? "degraded" : "down");
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("accountId", a.getId());
                    r.put("status", a.getStatus());
                    r.put("sessionActive", active);
                    r.put("broker", a.getBrokerId());
                    r.put("brokerName", BrokerAccountDto.from(a).getBrokerName());
                    r.put("clientId", a.getClientId());
                    r.put("connectionHealth", health);
                    r.put("lastSyncedAt", a.getSessionExpires() != null ? Instant.now().toString() : null);
                    r.put("expiresAt", a.getSessionExpires() != null ? a.getSessionExpires().toString() : null);
                    return r;
                });
    }

    // 3.8b Test connection — tries to fetch margin to verify broker is reachable
    public Mono<Map<String, Object>> testConnection(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .flatMap(a -> {
                    if (!a.isSessionActive() || a.getAccessToken() == null) {
                        return Mono.just(Map.<String, Object>of(
                                "accountId", a.getId().toString(),
                                "connectionHealth", "down",
                                "message", "No active session. Login first."));
                    }
                    // Try fetching margin as a health check
                    return getMargin(accountId, userId)
                            .map(margin -> {
                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put("accountId", a.getId().toString());
                                r.put("connectionHealth", "good");
                                r.put("sessionActive", true);
                                r.put("margin", margin.getOrDefault("availableMargin", 0));
                                r.put("brokerName", BrokerAccountDto.from(a).getBrokerName());
                                r.put("message", "Connection successful");
                                return (Map<String, Object>) r;
                            })
                            .onErrorResume(e -> {
                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put("accountId", a.getId().toString());
                                r.put("connectionHealth", "degraded");
                                r.put("message", "Connection issue: " + e.getMessage());
                                return Mono.just(r);
                            });
                });
    }

    // 3.9 Get margin (real for all live brokers)
    public Mono<Map<String, Object>> getMargin(UUID accountId, UUID userId) {
        return getActiveAccount(accountId, userId).flatMap(a -> {
            switch (a.getBrokerId()) {
                case "GROWW":
                    return growwClient.getMargin(a.getAccessToken()).map(resp -> parseGrowwMargin(resp));
                case "ZERODHA":
                    return zerodhaClient.getMargins(platformConfig.getZerodha().getApiKey(), a.getAccessToken()).map(resp -> parseZerodhaMargin(resp));
                case "FYERS":
                    String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + a.getAccessToken();
                    return fyersClient.getFunds(fyersAuth).map(resp -> parseFyersMargin(resp));
                case "UPSTOX":
                    return upstoxClient.getFundsMargin(a.getAccessToken())
                            .map(resp -> parseUpstoxMargin(resp))
                            .onErrorResume(e -> {
                                log.error("UPSTOX_MARGIN_ERROR: {}", e.getMessage());
                                Map<String, Object> fallback = new LinkedHashMap<>();
                                fallback.put("availableMargin", 0);
                                fallback.put("usedMargin", 0);
                                fallback.put("totalFunds", 0);
                                fallback.put("collateral", 0);
                                fallback.put("error", e.getMessage());
                                return Mono.just(fallback);
                            });
                case "DHAN":
                    return dhanClient.getFunds(a.getAccessToken())
                            .map(resp -> parseDhanMargin(resp))
                            .onErrorResume(e -> {
                                log.error("DHAN_MARGIN_ERROR: {}", e.getMessage());
                                Map<String, Object> fallback = new LinkedHashMap<>();
                                fallback.put("availableMargin", 0);
                                fallback.put("usedMargin", 0);
                                fallback.put("totalFunds", 0);
                                fallback.put("collateral", 0);
                                fallback.put("error", e.getMessage());
                                return Mono.just(fallback);
                            });
                default:
                    return Mono.just(mockMargin());
            }
        });
    }

    // 3.10 Get positions (real for all live brokers)
    public Mono<Map<String, Object>> getPositions(UUID accountId, UUID userId) {
        return getActiveAccount(accountId, userId).flatMap(a -> {
            switch (a.getBrokerId()) {
                case "GROWW":
                    return growwClient.getPositions(a.getAccessToken(), null)
                            .map(resp -> {
                                Object payload = resp.get("payload");
                                return Map.<String, Object>of("positions", payload != null ? payload : List.of());
                            });
                case "ZERODHA":
                    return zerodhaClient.getPositions(platformConfig.getZerodha().getApiKey(), a.getAccessToken())
                            .map(resp -> {
                                Object data = resp.get("data");
                                return Map.<String, Object>of("positions", data != null ? data : List.of());
                            });
                case "FYERS":
                    String fyersPosAuth = platformConfig.getFyers().getApiKey() + ":" + a.getAccessToken();
                    return fyersClient.getPositions(fyersPosAuth)
                            .map(resp -> {
                                Object netPositions = resp.get("netPositions");
                                return Map.<String, Object>of("positions", netPositions != null ? netPositions : List.of());
                            });
                case "UPSTOX":
                    return upstoxClient.getPositions(a.getAccessToken())
                            .map(resp -> {
                                Object data = resp.get("data");
                                return Map.<String, Object>of("positions", data != null ? data : List.of());
                            });
                case "DHAN":
                    return dhanClient.getPositions(a.getAccessToken())
                            .map(resp -> Map.<String, Object>of("positions", resp))
                            .onErrorResume(e -> Mono.just(Map.of("positions", List.of(), "error", e.getMessage())));
                default:
                    return Mono.just(Map.<String, Object>of("positions", List.of()));
            }
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
            Map.of("brokerId", "ZERODHA", "name", "Zerodha", "apiStatus", "UP", "latencyMs", 0, "lastChecked", Instant.now().toString()),
            Map.of("brokerId", "FYERS", "name", "Fyers", "apiStatus", "UP", "latencyMs", 0, "lastChecked", Instant.now().toString()),
            Map.of("brokerId", "UPSTOX", "name", "Upstox", "apiStatus", "UP", "latencyMs", 0, "lastChecked", Instant.now().toString()),
            Map.of("brokerId", "ANGELONE", "name", "Angel One", "apiStatus", "MOCK", "latencyMs", 0, "lastChecked", Instant.now().toString()),
            Map.of("brokerId", "DHAN", "name", "Dhan", "apiStatus", "MOCK", "latencyMs", 0, "lastChecked", Instant.now().toString())
        );
        return Mono.just(Map.of("brokers", statuses));
    }

    // --- Orders ---
    public Mono<Map<String, Object>> getOrders(UUID accountId, UUID userId) {
        return getActiveAccount(accountId, userId).flatMap(a -> {
            switch (a.getBrokerId()) {
                case "GROWW":
                    return growwClient.listOrders(a.getAccessToken(), null)
                            .map(resp -> Map.<String, Object>of("orders", resp.getOrDefault("payload", resp)));
                case "ZERODHA":
                    return zerodhaClient.getOrders(platformConfig.getZerodha().getApiKey(), a.getAccessToken())
                            .map(resp -> Map.<String, Object>of("orders", resp.getOrDefault("data", resp)));
                case "FYERS":
                    String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + a.getAccessToken();
                    return fyersClient.getOrders(fyersAuth)
                            .map(resp -> Map.<String, Object>of("orders", resp.getOrDefault("orderBook", resp)));
                case "UPSTOX":
                    return upstoxClient.getOrders(a.getAccessToken())
                            .map(resp -> Map.<String, Object>of("orders", resp.getOrDefault("data", resp)));
                case "DHAN":
                    return dhanClient.getOrders(a.getAccessToken())
                            .map(resp -> Map.<String, Object>of("orders", resp));
                default:
                    return Mono.just(Map.<String, Object>of("orders", List.of()));
            }
        });
    }

    // --- Trades ---
    public Mono<Map<String, Object>> getTrades(UUID accountId, UUID userId) {
        return getActiveAccount(accountId, userId).flatMap(a -> {
            switch (a.getBrokerId()) {
                case "GROWW":
                    return growwClient.listTrades(a.getAccessToken(), null)
                            .map(resp -> Map.<String, Object>of("trades", resp.getOrDefault("payload", resp)));
                case "ZERODHA":
                    return zerodhaClient.getTrades(platformConfig.getZerodha().getApiKey(), a.getAccessToken())
                            .map(resp -> Map.<String, Object>of("trades", resp.getOrDefault("data", resp)));
                case "FYERS":
                    String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + a.getAccessToken();
                    return fyersClient.getTrades(fyersAuth)
                            .map(resp -> Map.<String, Object>of("trades", resp.getOrDefault("tradeBook", resp)));
                case "UPSTOX":
                    return upstoxClient.getTrades(a.getAccessToken())
                            .map(resp -> Map.<String, Object>of("trades", resp.getOrDefault("data", resp)));
                case "DHAN":
                    return dhanClient.getTrades(a.getAccessToken())
                            .map(resp -> Map.<String, Object>of("trades", resp));
                default:
                    return Mono.just(Map.<String, Object>of("trades", List.of()));
            }
        });
    }

    // --- Holdings ---
    public Mono<Map<String, Object>> getHoldings(UUID accountId, UUID userId) {
        return getActiveAccount(accountId, userId).flatMap(a -> {
            switch (a.getBrokerId()) {
                case "GROWW":
                    return growwClient.getHoldings(a.getAccessToken())
                            .map(resp -> Map.<String, Object>of("holdings", resp.getOrDefault("payload", resp)));
                case "ZERODHA":
                    return zerodhaClient.getHoldings(platformConfig.getZerodha().getApiKey(), a.getAccessToken())
                            .map(resp -> Map.<String, Object>of("holdings", resp.getOrDefault("data", resp)));
                case "FYERS":
                    String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + a.getAccessToken();
                    return fyersClient.getHoldings(fyersAuth)
                            .map(resp -> Map.<String, Object>of("holdings", resp.getOrDefault("holdings", resp)));
                case "UPSTOX":
                    return upstoxClient.getHoldings(a.getAccessToken())
                            .map(resp -> Map.<String, Object>of("holdings", resp.getOrDefault("data", resp)));
                case "DHAN":
                    return dhanClient.getHoldings(a.getAccessToken())
                            .map(resp -> Map.<String, Object>of("holdings", resp));
                default:
                    return Mono.just(Map.<String, Object>of("holdings", List.of()));
            }
        });
    }

    // --- Close Position (place market order to close) ---
    public Mono<Map<String, Object>> closePosition(UUID accountId, UUID userId, Map<String, Object> body) {
        return getActiveAccount(accountId, userId).flatMap(a -> {
            String symbol = (String) body.get("symbol");
            Object qtyObj = body.get("qty");
            int qty = qtyObj instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(qtyObj));
            String type = (String) body.getOrDefault("type", "SELL");
            String product = (String) body.getOrDefault("product", "MIS");

            switch (a.getBrokerId()) {
                case "GROWW":
                    return growwClient.placeOrder(a.getAccessToken(), Map.of(
                            "symbol", symbol, "qty", qty, "type", type.equalsIgnoreCase("BUY") ? "BUY" : "SELL",
                            "product", product, "order_type", "MARKET", "price", 0
                    )).map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                case "ZERODHA":
                    return zerodhaClient.placeOrder(platformConfig.getZerodha().getApiKey(), a.getAccessToken(), Map.of(
                            "tradingsymbol", symbol, "quantity", qty, "transaction_type", type,
                            "product", product, "order_type", "MARKET", "exchange", "NSE"
                    )).map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                case "FYERS": {
                    String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + a.getAccessToken();
                    int side = type.equalsIgnoreCase("BUY") ? 1 : -1;
                    return fyersClient.placeOrder(fyersAuth, Map.of(
                            "symbol", symbol, "qty", qty, "side", side,
                            "productType", product, "type", 2, "limitPrice", 0, "stopPrice", 0
                    )).map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                }
                case "UPSTOX":
                    return upstoxClient.placeOrder(a.getAccessToken(), Map.of(
                            "instrument_token", symbol, "quantity", qty, "transaction_type", type,
                            "product", product, "order_type", "MARKET", "price", 0
                    )).map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                case "DHAN":
                    return dhanClient.placeOrder(a.getAccessToken(), Map.of(
                            "tradingSymbol", symbol, "quantity", qty, "transactionType", type,
                            "productType", product, "orderType", "MARKET", "exchangeSegment", "NSE_EQ"
                    )).map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                default:
                    return Mono.just(Map.<String, Object>of("message", "Unsupported broker"));
            }
        });
    }

    // --- Cancel Order ---
    public Mono<Map<String, Object>> cancelOrder(UUID accountId, UUID userId, String orderId) {
        return getActiveAccount(accountId, userId).flatMap(a -> {
            switch (a.getBrokerId()) {
                case "GROWW":
                    return growwClient.cancelOrder(a.getAccessToken(), orderId, "EQ")
                            .map(resp -> Map.<String, Object>of("message", "Order cancelled", "response", resp));
                case "ZERODHA":
                    return zerodhaClient.cancelOrder(platformConfig.getZerodha().getApiKey(), a.getAccessToken(), orderId)
                            .map(resp -> Map.<String, Object>of("message", "Order cancelled", "response", resp));
                case "FYERS": {
                    String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + a.getAccessToken();
                    return fyersClient.cancelOrder(fyersAuth, orderId)
                            .map(resp -> Map.<String, Object>of("message", "Order cancelled", "response", resp));
                }
                case "UPSTOX":
                    return upstoxClient.cancelOrder(a.getAccessToken(), orderId)
                            .map(resp -> Map.<String, Object>of("message", "Order cancelled", "response", resp));
                case "DHAN":
                    return dhanClient.cancelOrder(a.getAccessToken(), orderId)
                            .map(resp -> Map.<String, Object>of("message", "Order cancelled", "response", resp));
                default:
                    return Mono.just(Map.<String, Object>of("message", "Unsupported broker"));
            }
        });
    }

    // --- Margin parsers ---
    private Map<String, Object> parseGrowwMargin(Map resp) {
        Object payload = resp.get("payload");
        if (payload instanceof Map p) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("availableMargin", p.getOrDefault("clear_cash", 0));
            r.put("usedMargin", p.getOrDefault("net_margin_used", 0));
            r.put("totalFunds", toDouble(p.getOrDefault("clear_cash", 0)) + toDouble(p.getOrDefault("net_margin_used", 0)));
            r.put("collateral", p.getOrDefault("collateral_available", 0));
            return r;
        }
        return Map.of("raw", resp);
    }

    private Map<String, Object> parseZerodhaMargin(Map resp) {
        Map<String, Object> r = new LinkedHashMap<>();
        Object data = resp.get("data");
        if (data instanceof Map d) {
            Object equity = d.get("equity");
            if (equity instanceof Map eq) {
                Object avail = eq.get("available");
                Object util = eq.get("utilised");
                double cash = avail instanceof Map a ? toDouble(((Map) a).getOrDefault("cash", 0)) : 0;
                double debits = util instanceof Map u ? toDouble(((Map) u).getOrDefault("debits", 0)) : 0;
                r.put("availableMargin", cash - debits);
                r.put("usedMargin", debits);
                r.put("totalFunds", cash);
                r.put("collateral", avail instanceof Map a2 ? toDouble(((Map) a2).getOrDefault("collateral", 0)) : 0);
                return r;
            }
        }
        r.put("raw", resp);
        return r;
    }

    private Map<String, Object> parseFyersMargin(Map resp) {
        Map<String, Object> r = new LinkedHashMap<>();
        Object fundLimit = resp.get("fund_limit");
        if (fundLimit instanceof List fl) {
            double available = 0, used = 0, total = 0;
            for (Object item : fl) {
                if (item instanceof Map m) {
                    String title = String.valueOf(m.getOrDefault("title", ""));
                    double val = toDouble(m.getOrDefault("equityAmount", m.getOrDefault("amount", 0)));
                    if ("Total Balance".equalsIgnoreCase(title)) total = val;
                    if ("Available Balance".equalsIgnoreCase(title)) available = val;
                    if ("Utilized Amount".equalsIgnoreCase(title)) used = val;
                }
            }
            r.put("availableMargin", available);
            r.put("usedMargin", used);
            r.put("totalFunds", total);
            r.put("collateral", 0);
            return r;
        }
        // Fallback: return raw response as margin info
        r.put("availableMargin", toDouble(resp.getOrDefault("availableMargin", 0)));
        r.put("usedMargin", toDouble(resp.getOrDefault("usedMargin", 0)));
        r.put("totalFunds", toDouble(resp.getOrDefault("totalFunds", 0)));
        r.put("collateral", 0);
        r.put("raw", resp);
        return r;
    }

    private Map<String, Object> parseUpstoxMargin(Map resp) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            Object data = resp.get("data");
            if (data instanceof Map d) {
                // Try equity key first
                Object equity = d.get("equity");
                if (equity instanceof Map eq) {
                    r.put("availableMargin", toDouble(eq.getOrDefault("available_margin", 0)));
                    r.put("usedMargin", toDouble(eq.getOrDefault("used_margin", 0)));
                    r.put("totalFunds", toDouble(eq.getOrDefault("available_margin", 0)) + toDouble(eq.getOrDefault("used_margin", 0)));
                    r.put("collateral", toDouble(eq.getOrDefault("collateral", 0)));
                    return r;
                }
                // Maybe data itself has the margin fields
                r.put("availableMargin", toDouble(d.getOrDefault("available_margin", 0)));
                r.put("usedMargin", toDouble(d.getOrDefault("used_margin", 0)));
                r.put("totalFunds", toDouble(d.getOrDefault("available_margin", 0)) + toDouble(d.getOrDefault("used_margin", 0)));
                r.put("collateral", 0);
                return r;
            }
        } catch (Exception e) {
            // fallback
        }
        r.put("availableMargin", 0);
        r.put("usedMargin", 0);
        r.put("totalFunds", 0);
        r.put("collateral", 0);
        r.put("raw", resp);
        return r;
    }

    private Map<String, Object> parseDhanMargin(Map resp) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            // Dhan returns fields at root level (note: Dhan has typo "availabelBalance")
            r.put("availableMargin", toDouble(resp.getOrDefault("availabelBalance", resp.getOrDefault("availableBalance", 0))));
            r.put("usedMargin", toDouble(resp.getOrDefault("utilizedAmount", 0)));
            r.put("totalFunds", toDouble(resp.getOrDefault("sodLimit", 0)));
            r.put("collateral", toDouble(resp.getOrDefault("collateralAmount", 0)));
            return r;
        } catch (Exception e) { /* fallback */ }
        r.put("availableMargin", 0);
        r.put("usedMargin", 0);
        r.put("totalFunds", 0);
        r.put("collateral", 0);
        r.put("raw", resp);
        return r;
    }

    // --- Connection Signal (like mobile network bars: 1-4) ---
    public Mono<Map<String, Object>> getConnectionSignal(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .flatMap(a -> {
                    // No session at all → signal 0 (no bars, red)
                    if (!a.isSessionActive() || a.getAccessToken() == null) {
                        return Mono.just(buildSignal(a, 0, "disconnected", "No active session. Login required."));
                    }
                    // Session expired → signal 1 (1 bar, red)
                    if (a.getSessionExpires() != null && a.getSessionExpires().isBefore(Instant.now())) {
                        return Mono.just(buildSignal(a, 1, "expired", "Session expired. Re-login required."));
                    }
                    // Session exists — try a real API call (margin) to check latency
                    long start = System.currentTimeMillis();
                    return getMargin(accountId, userId)
                            .map(margin -> {
                                long latency = System.currentTimeMillis() - start;
                                int bars;
                                String quality;
                                String msg;
                                if (latency < 500) {
                                    bars = 4; quality = "excellent"; msg = "Connection excellent (" + latency + "ms)";
                                } else if (latency < 1500) {
                                    bars = 3; quality = "good"; msg = "Connection good (" + latency + "ms)";
                                } else if (latency < 3000) {
                                    bars = 2; quality = "fair"; msg = "Connection slow (" + latency + "ms)";
                                } else {
                                    bars = 1; quality = "poor"; msg = "Connection very slow (" + latency + "ms)";
                                }
                                Map<String, Object> r = buildSignal(a, bars, quality, msg);
                                r.put("latencyMs", latency);
                                r.put("marginAvailable", margin.getOrDefault("availableMargin", 0));
                                return r;
                            })
                            .onErrorResume(e -> {
                                // API call failed → signal 1 (degraded)
                                return Mono.just(buildSignal(a, 1, "error", "Broker API error: " + e.getMessage()));
                            });
                });
    }

    private Map<String, Object> buildSignal(BrokerAccount a, int bars, String quality, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("accountId", a.getId().toString());
        r.put("brokerId", a.getBrokerId());
        r.put("brokerName", BrokerAccountDto.from(a).getBrokerName());
        r.put("signal", bars);           // 0-4 (like mobile bars)
        r.put("maxSignal", 4);
        r.put("quality", quality);       // disconnected, expired, error, poor, fair, good, excellent
        r.put("color", bars >= 3 ? "green" : bars == 2 ? "yellow" : "red");
        r.put("message", message);
        r.put("sessionActive", a.isSessionActive());
        return r;
    }

    // --- Dashboard (all-in-one: profile + margin + positions + holdings + orders) ---
    public Mono<Map<String, Object>> getDashboard(UUID accountId, UUID userId) {
        return getActiveAccount(accountId, userId).flatMap(a -> {
            Mono<Map<String, Object>> marginMono = getMargin(accountId, userId)
                    .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));
            Mono<Map<String, Object>> positionsMono = getPositions(accountId, userId)
                    .onErrorResume(e -> Mono.just(Map.of("positions", List.of(), "error", e.getMessage())));
            Mono<Map<String, Object>> holdingsMono = getHoldings(accountId, userId)
                    .onErrorResume(e -> Mono.just(Map.of("holdings", List.of(), "error", e.getMessage())));
            Mono<Map<String, Object>> ordersMono = getOrders(accountId, userId)
                    .onErrorResume(e -> Mono.just(Map.of("orders", List.of(), "error", e.getMessage())));
            Mono<Map<String, Object>> profileMono = getBrokerProfile(a)
                    .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));

            return Mono.zip(marginMono, positionsMono, holdingsMono, ordersMono, profileMono)
                    .map(tuple -> {
                        Map<String, Object> margin = tuple.getT1();
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("accountId", a.getId().toString());
                        r.put("brokerId", a.getBrokerId());
                        r.put("brokerName", BrokerAccountDto.from(a).getBrokerName());
                        r.put("clientId", a.getClientId());
                        r.put("nickname", a.getNickname());
                        r.put("status", a.getStatus());
                        r.put("sessionActive", a.isSessionActive());
                        // Connection signal (4=excellent, 3=good, 2=fair, 1=poor, 0=disconnected)
                        boolean hasError = margin.containsKey("error");
                        int signal = hasError ? 1 : 4;
                        r.put("signal", Map.of(
                                "bars", signal,
                                "maxBars", 4,
                                "quality", hasError ? "error" : "excellent",
                                "color", signal >= 3 ? "green" : signal == 2 ? "yellow" : "red"
                        ));
                        // Balance alert
                        double available = toDouble(margin.getOrDefault("availableMargin", 0));
                        String alertLevel = available < 1000 ? "CRITICAL" : available < 5000 ? "WARNING" : available < 10000 ? "LOW" : "OK";
                        r.put("balanceAlert", Map.of("level", alertLevel, "availableMargin", available));
                        r.put("profile", tuple.getT5());
                        r.put("margin", margin);
                        r.put("positions", tuple.getT2().getOrDefault("positions", List.of()));
                        r.put("holdings", tuple.getT3().getOrDefault("holdings", List.of()));
                        r.put("orders", tuple.getT4().getOrDefault("orders", List.of()));
                        return r;
                    });
        });
    }

    private Mono<Map<String, Object>> getBrokerProfile(BrokerAccount a) {
        switch (a.getBrokerId()) {
            case "GROWW":
                return growwClient.getProfile(a.getAccessToken())
                        .map(resp -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            Object payload = resp.get("payload");
                            if (payload instanceof Map m) {
                                p.put("name", m.getOrDefault("name", ""));
                                p.put("email", m.getOrDefault("email", ""));
                                p.put("clientId", m.getOrDefault("client_id", ""));
                                p.put("broker", "Groww");
                            } else {
                                p.put("raw", resp);
                                p.put("broker", "Groww");
                            }
                            return p;
                        });
            case "ZERODHA":
                return zerodhaClient.getProfile(platformConfig.getZerodha().getApiKey(), a.getAccessToken())
                        .map(resp -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            Object data = resp.get("data");
                            if (data instanceof Map d) {
                                p.put("name", d.getOrDefault("user_name", ""));
                                p.put("email", d.getOrDefault("email", ""));
                                p.put("clientId", d.getOrDefault("user_id", ""));
                                p.put("broker", "Zerodha");
                                p.put("exchanges", d.getOrDefault("exchanges", List.of()));
                                p.put("products", d.getOrDefault("products", List.of()));
                            } else {
                                p.put("raw", resp);
                                p.put("broker", "Zerodha");
                            }
                            return p;
                        });
            case "FYERS": {
                String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + a.getAccessToken();
                return fyersClient.getProfile(fyersAuth)
                        .map(resp -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            Object data = resp.get("data");
                            if (data instanceof Map d) {
                                p.put("name", d.getOrDefault("name", ""));
                                p.put("email", d.getOrDefault("email_id", ""));
                                p.put("clientId", d.getOrDefault("fy_id", ""));
                                p.put("broker", "Fyers");
                                p.put("pan", d.getOrDefault("PAN", ""));
                            } else {
                                p.put("raw", resp);
                                p.put("broker", "Fyers");
                            }
                            return p;
                        });
            }
            case "UPSTOX":
                return upstoxClient.getProfile(a.getAccessToken())
                        .map(resp -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            Object data = resp.get("data");
                            if (data instanceof Map d) {
                                p.put("name", d.getOrDefault("user_name", ""));
                                p.put("email", d.getOrDefault("email", ""));
                                p.put("clientId", d.getOrDefault("user_id", ""));
                                p.put("broker", "Upstox");
                                p.put("exchanges", d.getOrDefault("exchanges", List.of()));
                            } else {
                                p.put("raw", resp);
                                p.put("broker", "Upstox");
                            }
                            return p;
                        });
            case "DHAN":
                return dhanClient.getProfile(a.getAccessToken())
                        .map(resp -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("name", resp.getOrDefault("userName", resp.getOrDefault("name", "")));
                            p.put("email", resp.getOrDefault("emailId", resp.getOrDefault("email", "")));
                            p.put("clientId", resp.getOrDefault("dhanClientId", resp.getOrDefault("clientId", "")));
                            p.put("broker", "Dhan");
                            return p;
                        });
            default:
                return Mono.just(Map.of("broker", a.getBrokerId(), "message", "Profile not available"));
        }
    }

    // --- Helpers ---
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

    private Map<String, Object> growwBrokerInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("brokerId", "GROWW");
        m.put("name", "Groww");
        m.put("isActive", true);
        m.put("loginMethod", "token");
        m.put("loginOptions", List.of(
            Map.of("method", "accessToken", "description", "Paste access token from Groww settings (no API key needed)", "requiredFields", List.of("accessToken")),
            Map.of("method", "apiKeyWithTotp", "description", "API key + TOTP code from authenticator app", "requiredFields", List.of("apiKey", "totpCode"))
        ));
        m.put("note", "Groww requires per-user credentials. Each user generates their own from Groww settings.");
        return m;
    }

    private Map<String, Object> brokerInfo(String id, String name, boolean active, List<String> fields, String loginMethod, String loginField) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("brokerId", id);
        m.put("name", name);
        m.put("requiredFields", fields);
        m.put("isActive", active);
        m.put("loginMethod", loginMethod);  // "secret" = just api key+secret, "oauth" = needs browser redirect
        if (loginField != null) m.put("loginField", loginField);  // field name to send in login request
        return m;
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception e) { return 0; }
    }
}
