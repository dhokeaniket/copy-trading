package com.copytrading.broker;

import com.copytrading.broker.dhan.DhanApiClient;
import com.copytrading.broker.dto.*;
import com.copytrading.broker.fyers.FyersApiClient;
import com.copytrading.broker.groww.GrowwApiClient;
import com.copytrading.broker.upstox.UpstoxApiClient;
import com.copytrading.broker.zerodha.ZerodhaApiClient;
import com.copytrading.broker.angelone.AngelOneApiClient;
import com.copytrading.notification.NotificationService;
import com.copytrading.security.BrokerCredentials;
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
    private final AngelOneApiClient angelOneClient;
    private final PlatformBrokerConfig platformConfig;
    private final BrokerCredentials credentials;
    private final NotificationService notifications;

    public BrokerAccountService(BrokerAccountRepository repo, GrowwApiClient growwClient,
                                ZerodhaApiClient zerodhaClient, FyersApiClient fyersClient,
                                UpstoxApiClient upstoxClient, DhanApiClient dhanClient,
                                AngelOneApiClient angelOneClient,
                                PlatformBrokerConfig platformConfig,
                                BrokerCredentials credentials,
                                NotificationService notifications) {
        this.repo = repo;
        this.growwClient = growwClient;
        this.zerodhaClient = zerodhaClient;
        this.fyersClient = fyersClient;
        this.upstoxClient = upstoxClient;
        this.dhanClient = dhanClient;
        this.angelOneClient = angelOneClient;
        this.platformConfig = platformConfig;
        this.credentials = credentials;
        this.notifications = notifications;
    }

    private String sessionToken(BrokerAccount a) {
        return credentials.accessToken(a);
    }

    // 3.1 List supported brokers
    public Mono<Map<String, Object>> listBrokers() {
        List<Map<String, Object>> brokers = List.of(
            growwBrokerInfo(),
            brokerInfo("ZERODHA", "Zerodha", true, List.of(), "oauth", "requestToken"),
            brokerInfo("FYERS", "Fyers", true, List.of(), "oauth", "authCode"),
            brokerInfo("UPSTOX", "Upstox", true, List.of(), "oauth", "authCode"),
            brokerInfo("ANGELONE", "Angel One", true, List.of(), "totp", "totpCode"),
            dhanBrokerInfo()
        );
        return Mono.just(Map.of("brokers", brokers));
    }

    private Map<String, Object> dhanBrokerInfo() {
        return loginConfigForBroker("DHAN");
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

        // For OAuth brokers, use platform-level keys; for Groww, also fall back to platform keys
        PlatformBrokerConfig.BrokerCreds platformCreds = platformConfig.getFor(broker);
        if (platformCreds != null && platformCreds.getApiKey() != null && !platformCreds.getApiKey().isBlank()) {
            // Use platform keys if user didn't provide their own
            if (req.getApiKey() == null || req.getApiKey().isBlank()) {
                a.setApiKey(platformCreds.getApiKey());
                a.setApiSecret(platformCreds.getApiSecret() != null ? platformCreds.getApiSecret() : "");
            } else {
                a.setApiKey(req.getApiKey());
                a.setApiSecret(req.getApiSecret() != null ? req.getApiSecret() : "");
            }
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
        credentials.encryptSensitiveFields(a);
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
                    if (req.getClientId() != null) a.setClientId(req.getClientId());
                    credentials.encryptSensitiveFields(a);
                    return repo.save(a).thenReturn(Map.of("message", "Account updated"));
                });
    }

    /** Login UI config for reconnect — same options as GET /api/v1/brokers (e.g. Groww accessToken + apiKeyWithTotp). */
    public Mono<Map<String, Object>> getLoginOptions(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .map(a -> {
                    Map<String, Object> config = loginConfigForAccount(a, null);
                    logLoginOptions(accountId, userId, a, config, "GET_LOGIN_OPTIONS");
                    return config;
                });
    }

    /** End session but keep account; returns full login options for reconnect; pushes notification. */
    public Mono<Map<String, Object>> disconnectBroker(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .flatMap(a -> {
                    clearBrokerSession(a);
                    clearStoredCredentialsOnDisconnect(a);
                    credentials.encryptSensitiveFields(a);
                    return repo.save(a)
                            .flatMap(saved -> notifyBrokerReconnect(userId, saved, "BROKER_DISCONNECTED",
                                    "Broker disconnected",
                                    "Your " + BrokerAccountDto.from(saved).getBrokerName()
                                            + " account was disconnected. Tap Reconnect to sign in again.")
                                    .thenReturn(buildDisconnectResponse(saved)))
                            .doOnNext(r -> log.info(
                                    "BROKER_DISCONNECTED accountId={} userId={} broker={} reason=USER_DISCONNECT options={}",
                                    accountId, userId, a.getBrokerId(), r.get("loginOptionMethods")));
                });
    }

    // Set access token directly (for tokens generated from broker dashboard)
    public Mono<Map<String, Object>> setAccessToken(UUID accountId, UUID userId, String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "accessToken is required"));
        }
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .flatMap(a -> {
                    a.setAccessToken(accessToken);
                    a.setSessionActive(true);
                    a.setStatus("ACTIVE");
                    a.setSessionExpires(java.time.Instant.now().plusSeconds(86400));
                    credentials.encryptSensitiveFields(a);
                    return repo.save(a).map(saved -> {
                        Map<String, Object> r = new java.util.LinkedHashMap<>();
                        r.put("status", "SESSION_ACTIVE");
                        r.put("message", "Access token saved. Session active until " + saved.getSessionExpires());
                        r.put("broker", saved.getBrokerId());
                        r.put("accountId", saved.getId().toString());
                        r.put("loginMethod", "accessToken");
                        log.info("BROKER_LOGIN_SUCCESS accountId={} userId={} broker={} method=accessToken",
                                accountId, userId, saved.getBrokerId());
                        return r;
                    });
                });
    }

    // 3.6 Delete account
    public Mono<Map<String, String>> deleteAccount(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .flatMap(a -> repo.delete(a)
                        .thenReturn(Map.of("message", "Account unlinked"))
                        .onErrorResume(e -> {
                            clearBrokerSession(a);
                            clearStoredCredentials(a);
                            credentials.encryptSensitiveFields(a);
                            return repo.save(a)
                                    .flatMap(saved -> notifyBrokerReconnect(userId, saved, "BROKER_DISCONNECTED",
                                            "Broker disconnected",
                                            "Your " + BrokerAccountDto.from(saved).getBrokerName()
                                                    + " account was disconnected. Unsubscribe from masters first to fully remove.")
                                            .thenReturn(Map.of(
                                                    "message", "Account disconnected. Unsubscribe from masters first to fully remove. Use login-options to reconnect.")));
                        }));
    }

    // 3.7 Login to broker (create session)
    public Mono<Map<String, Object>> loginToBroker(UUID accountId, UUID userId, BrokerLoginRequest req) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .flatMap(a -> {
                    String loginMethod = detectLoginMethod(a, req);
                    log.info("BROKER_LOGIN_START accountId={} userId={} broker={} method={}",
                            accountId, userId, a.getBrokerId(), loginMethod);
                    switch (a.getBrokerId()) {
                        case "GROWW":    return loginGroww(a, req);
                        case "ZERODHA":  return loginZerodha(a, req);
                        case "FYERS":    return loginFyers(a, req);
                        case "UPSTOX":   return loginUpstox(a, req);
                        case "DHAN":     return loginDhan(a, req);
                        case "ANGELONE": return loginAngelOne(a, req);
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
        // Resolve API key: prefer per-user key, fall back to platform-level key
        String apiKey = a.getApiKey();
        String apiSecret = a.getApiSecret();
        var platformCreds = platformConfig.getGroww();
        if ((apiKey == null || apiKey.isBlank()) && platformCreds != null && platformCreds.getApiKey() != null && !platformCreds.getApiKey().isBlank()) {
            apiKey = platformCreds.getApiKey();
            apiSecret = platformCreds.getApiSecret();
        }
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No Groww API key configured. Set apiKey on the broker account or configure platform-level Groww credentials."));
        }

        Mono<Map> tokenMono;
        if (req != null && req.getTotpCode() != null && !req.getTotpCode().isBlank()) {
            tokenMono = growwClient.generateToken(apiKey, req.getTotpCode());
        } else {
            tokenMono = growwClient.generateTokenWithSecret(apiKey, apiSecret);
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
                        r -> {
                            if (r.get("data") instanceof Map<?, ?> d) {
                                Object t = d.get("access_token");
                                if (t != null) return t.toString();
                            }
                            Object t = r.get("access_token");
                            return t != null ? t.toString() : null;
                        }))
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

        // Save clientId from request if provided (ensures it's always set)
        if (req != null && req.getClientId() != null && !req.getClientId().isBlank()) {
            a.setClientId(req.getClientId());
        }

        // If tokenId provided (from browser callback), exchange for access token
        if (req != null && req.getAuthCode() != null && !req.getAuthCode().isBlank()) {
            return dhanClient.consumeConsent(creds.getApiKey(), creds.getApiSecret(), req.getAuthCode())
                    .flatMap(resp -> {
                        // Dhan response contains dhanClientId — save it for order placement
                        Object cid = resp.get("dhanClientId");
                        if (cid == null) cid = resp.get("clientId");
                        if (cid != null && !cid.toString().isBlank()) {
                            a.setClientId(cid.toString());
                        }
                        return extractAndSaveSession(a, resp, "Dhan",
                                r -> (String) r.get("accessToken"))
                                .flatMap(result -> {
                                    // If clientId still empty, fetch from profile
                                    if (a.getClientId() == null || a.getClientId().isBlank()) {
                                        return dhanClient.getProfile(sessionToken(a))
                                                .flatMap(profile -> {
                                                    Object pid = profile.get("dhanClientId");
                                                    if (pid == null) pid = profile.get("clientId");
                                                    if (pid != null && !pid.toString().isBlank()) {
                                                        a.setClientId(pid.toString());
                                                        return repo.save(a).thenReturn(result);
                                                    }
                                                    return Mono.just(result);
                                                })
                                                .onErrorResume(e2 -> Mono.just(result));
                                    }
                                    return Mono.just(result);
                                });
                    })
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

    // --- ANGEL ONE LOGIN (clientcode + password + TOTP) ---
    private Mono<Map<String, Object>> loginAngelOne(BrokerAccount a, BrokerLoginRequest req) {
        var creds = platformConfig.getAngelone();
        String apiKey = creds.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Angel One API key not configured on platform"));
        }
        if (req == null || req.getTotpCode() == null || req.getTotpCode().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "totpCode required for Angel One login. Also set clientId (your Angel One client code) and password via account update."));
        }
        // clientId = Angel One client code, apiSecret = password (stored on account)
        String clientCode = a.getClientId();
        String password = a.getApiSecret();
        if (clientCode == null || clientCode.isBlank() || password == null || password.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Angel One requires clientId (client code) and apiSecret (password) set on the broker account."));
        }
        return angelOneClient.login(apiKey, clientCode, password, req.getTotpCode())
                .flatMap(resp -> extractAndSaveSession(a, resp, "AngelOne",
                        r -> {
                            if (r.containsKey("data") && r.get("data") instanceof Map d) {
                                return (String) d.get("jwtToken");
                            }
                            return (String) r.get("jwtToken");
                        }))
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    log.error("ANGELONE_LOGIN_FAILED error={}", e.getMessage(), e);
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Angel One API error: " + e.getMessage()));
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
        credentials.encryptSensitiveFields(a);
        return repo.save(a).map(s -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "SESSION_ACTIVE");
            r.put("broker", brokerName);
            r.put("expiresAt", s.getSessionExpires().toString());
            log.info("BROKER_LOGIN_SUCCESS accountId={} userId={} broker={} method=oauth_or_totp",
                    s.getId(), s.getUserId(), s.getBrokerId());
            return r;
        });
    }

    // 3.7b Get OAuth URL + full loginOptions for browser-based login (all brokers)
    public Mono<Map<String, Object>> getOAuthUrl(UUID accountId, UUID userId, String redirectUri) {
        String redirect = (redirectUri != null && !redirectUri.isBlank()) ? redirectUri : platformConfig.getCallbackUrl();
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .map(a -> loginConfigForAccount(a, redirect));
    }

    // 3.8 Check session status (DB + live broker ping when session looks active)
    public Mono<Map<String, Object>> getSessionStatus(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .flatMap(a -> {
                    Map<String, Object> r = buildSessionStatusMap(a);
                    boolean active = Boolean.TRUE.equals(r.get("sessionActive"));
                    if (!active || sessionToken(a) == null) {
                        r.putAll(loginConfigForAccount(a, null));
                        r.put("reason", describeInactiveReason(a));
                        logSessionStatus(accountId, userId, r);
                        return Mono.just(r);
                    }
                    return getMargin(accountId, userId)
                            .map(margin -> {
                                applyLiveHealth(r, margin);
                                if (marginResponseHasError(margin) || !Boolean.TRUE.equals(r.get("sessionActive"))) {
                                    r.putAll(loginConfigForAccount(a, null));
                                    if (r.get("reason") == null) {
                                        r.put("reason", margin.getOrDefault("error", "Live broker check failed"));
                                    }
                                }
                                r.put("lastSyncedAt", Instant.now().toString());
                                logSessionStatus(accountId, userId, r);
                                return r;
                            })
                            .onErrorResume(e -> {
                                r.put("connectionHealth", "degraded");
                                r.put("liveCheckError", e.getMessage());
                                r.put("action", "RE_LOGIN");
                                r.put("errorCode", "SESSION_EXPIRED");
                                r.put("reason", e.getMessage());
                                r.putAll(loginConfigForAccount(a, null));
                                log.warn("BROKER_SESSION_LIVE_CHECK_FAILED accountId={} userId={} broker={} reason={}",
                                        accountId, userId, a.getBrokerId(), e.getMessage());
                                logSessionStatus(accountId, userId, r);
                                return Mono.just(r);
                            });
                });
    }

    private Map<String, Object> buildSessionStatusMap(BrokerAccount a) {
        boolean active = a.isSessionActive() && a.getSessionExpires() != null
                && a.getSessionExpires().isAfter(Instant.now());
        String health = active ? "good" : (sessionToken(a) != null ? "degraded" : "down");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("accountId", a.getId());
        r.put("status", a.getStatus());
        r.put("sessionActive", active);
        r.put("broker", a.getBrokerId());
        r.put("brokerName", BrokerAccountDto.from(a).getBrokerName());
        r.put("clientId", a.getClientId());
        r.put("connectionHealth", health);
        r.put("lastSyncedAt", a.getLinkedAt() != null ? a.getLinkedAt().toString() : null);
        r.put("expiresAt", a.getSessionExpires() != null ? a.getSessionExpires().toString() : null);
        if (!active) {
            r.put("reason", describeInactiveReason(a));
            r.put("action", "RE_LOGIN");
        }
        return r;
    }

    private void applyLiveHealth(Map<String, Object> status, Map<String, Object> margin) {
        if (marginResponseHasError(margin)) {
            status.put("connectionHealth", "degraded");
            status.put("sessionActive", false);
            status.put("error", margin.get("error"));
            status.put("reason", margin.get("error"));
            status.put("errorCode", margin.getOrDefault("errorCode", "SESSION_EXPIRED"));
            status.put("action", margin.getOrDefault("action", "RE_LOGIN"));
            log.warn("BROKER_SESSION_DEGRADED accountId={} broker={} errorCode={} reason={}",
                    status.get("accountId"), status.get("broker"),
                    status.get("errorCode"), status.get("reason"));
        } else {
            status.put("connectionHealth", "good");
            status.put("availableMargin", margin.getOrDefault("availableMargin", 0));
        }
    }

    private boolean marginResponseHasError(Map<String, Object> margin) {
        return margin != null && margin.containsKey("error");
    }

    // 3.8b Test connection — tries to fetch margin to verify broker is reachable
    public Mono<Map<String, Object>> testConnection(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .flatMap(a -> {
                    if (!a.isSessionActive() || sessionToken(a) == null) {
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
                                r.put("brokerName", BrokerAccountDto.from(a).getBrokerName());
                                if (marginResponseHasError(margin)) {
                                    r.put("connectionHealth", "degraded");
                                    r.put("sessionActive", false);
                                    r.put("message", margin.get("error"));
                                    r.put("errorCode", margin.get("errorCode"));
                                    r.put("action", margin.get("action"));
                                    return (Map<String, Object>) r;
                                }
                                r.put("connectionHealth", "good");
                                r.put("sessionActive", true);
                                r.put("margin", margin.getOrDefault("availableMargin", 0));
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
        return getAccountOwned(accountId, userId).flatMap(a -> {
            if (!a.isSessionActive() || sessionToken(a) == null) {
                Map<String, Object> r = sessionRequiredResponse(a);
                r.put("availableMargin", 0); r.put("usedMargin", 0); r.put("totalFunds", 0); r.put("collateral", 0);
                return Mono.just(r);
            }
            Mono<Map<String, Object>> result;
            switch (a.getBrokerId()) {
                case "GROWW":
                    result = growwClient.getMargin(sessionToken(a)).map(resp -> parseGrowwMargin(resp)); break;
                case "ZERODHA":
                    result = zerodhaClient.getMargins(platformConfig.getZerodha().getApiKey(), sessionToken(a)).map(resp -> parseZerodhaMargin(resp)); break;
                case "FYERS":
                    String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + sessionToken(a);
                    result = fyersClient.getFunds(fyersAuth).map(resp -> parseFyersMargin(resp)); break;
                case "UPSTOX":
                    result = upstoxClient.getFundsMargin(sessionToken(a)).map(resp -> parseUpstoxMargin(resp)); break;
                case "DHAN":
                    result = dhanClient.getFunds(sessionToken(a)).map(resp -> parseDhanMargin(resp)); break;
                case "ANGELONE":
                    result = angelOneClient.getRMS(platformConfig.getAngelone().getApiKey(), sessionToken(a)).map(resp -> parseAngelOneMargin(resp)); break;
                default:
                    result = Mono.just(mockMargin()); break;
            }
            return result.onErrorResume(e -> {
                log.error("{}_MARGIN_ERROR accountId={} reason={}", a.getBrokerId(), accountId, e.getMessage());
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("availableMargin", 0); fallback.put("usedMargin", 0);
                fallback.put("totalFunds", 0); fallback.put("collateral", 0);
                String friendly = friendlyBrokerError(a.getBrokerId(), e.getMessage());
                fallback.put("error", friendly);
                fallback.put("reason", friendly);
                fallback.put("errorCode", "SESSION_EXPIRED");
                fallback.put("action", "RE_LOGIN");
                if (isAuthError(e)) {
                    a.setSessionActive(false);
                    a.setStatus("AUTH_REQUIRED");
                    a.setAccessToken(null);
                    return repo.save(a).thenReturn(fallback);
                }
                return Mono.just(fallback);
            });
        });
    }

    // 3.10 Get positions (real for all live brokers)
    public Mono<Map<String, Object>> getPositions(UUID accountId, UUID userId) {
        return getAccountOwned(accountId, userId).flatMap(a -> {
            if (!a.isSessionActive() || sessionToken(a) == null) {
                Map<String, Object> r = sessionRequiredResponse(a);
                r.put("positions", List.of());
                return Mono.just(r);
            }
            switch (a.getBrokerId()) {
                case "GROWW":
                    return growwClient.getPositions(sessionToken(a), null)
                            .map(resp -> {
                                Object payload = resp.get("payload");
                                return Map.<String, Object>of("positions", payload != null ? payload : List.of());
                            });
                case "ZERODHA":
                    return zerodhaClient.getPositions(platformConfig.getZerodha().getApiKey(), sessionToken(a))
                            .map(resp -> {
                                Object data = resp.get("data");
                                return Map.<String, Object>of("positions", data != null ? data : List.of());
                            });
                case "FYERS":
                    String fyersPosAuth = platformConfig.getFyers().getApiKey() + ":" + sessionToken(a);
                    return fyersClient.getPositions(fyersPosAuth)
                            .map(resp -> {
                                Object netPositions = resp.get("netPositions");
                                return Map.<String, Object>of("positions", netPositions != null ? netPositions : List.of());
                            });
                case "UPSTOX":
                    return upstoxClient.getPositions(sessionToken(a))
                            .map(resp -> {
                                Object data = resp.get("data");
                                return Map.<String, Object>of("positions", data != null ? data : List.of());
                            });
                case "DHAN":
                    return dhanClient.getPositions(sessionToken(a))
                            .map(resp -> Map.<String, Object>of("positions", resp));
                case "ANGELONE":
                    return angelOneClient.getPositions(platformConfig.getAngelone().getApiKey(), sessionToken(a))
                            .map(resp -> {
                                Object data = resp.get("data");
                                return Map.<String, Object>of("positions", data != null ? data : List.of());
                            });
                default:
                    return Mono.just(Map.<String, Object>of("positions", List.of()));
            }
        }).onErrorResume(e -> Mono.just(Map.of("positions", List.of(), "error", friendlyBrokerError("", e.getMessage()), "errorCode", "SESSION_EXPIRED", "action", "RE_LOGIN")));
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
            Map.of("brokerId", "ANGELONE", "name", "Angel One", "apiStatus", "UP", "latencyMs", 0, "lastChecked", Instant.now().toString()),
            Map.of("brokerId", "DHAN", "name", "Dhan", "apiStatus", "UP", "latencyMs", 0, "lastChecked", Instant.now().toString())
        );
        return Mono.just(Map.of("brokers", statuses));
    }

    // --- Orders ---
    public Mono<Map<String, Object>> getOrders(UUID accountId, UUID userId) {
        return getAccountOwned(accountId, userId).flatMap(a -> {
            if (!a.isSessionActive() || sessionToken(a) == null) {
                Map<String, Object> r = sessionRequiredResponse(a); r.put("orders", List.of()); return Mono.just(r);
            }
            switch (a.getBrokerId()) {
                case "GROWW":
                    return growwClient.listOrders(sessionToken(a), null)
                            .map(resp -> Map.<String, Object>of("orders", resp.getOrDefault("payload", resp)));
                case "ZERODHA":
                    return zerodhaClient.getOrders(platformConfig.getZerodha().getApiKey(), sessionToken(a))
                            .map(resp -> Map.<String, Object>of("orders", resp.getOrDefault("data", resp)));
                case "FYERS":
                    String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + sessionToken(a);
                    return fyersClient.getOrders(fyersAuth)
                            .map(resp -> Map.<String, Object>of("orders", resp.getOrDefault("orderBook", resp)));
                case "UPSTOX":
                    return upstoxClient.getOrders(sessionToken(a))
                            .map(resp -> Map.<String, Object>of("orders", resp.getOrDefault("data", resp)));
                case "DHAN":
                    return dhanClient.getOrders(sessionToken(a))
                            .map(resp -> Map.<String, Object>of("orders", resp.getOrDefault("orders", resp)))
                            .onErrorResume(e -> Mono.just(Map.of("orders", List.of(), "error", e.getMessage())));
                case "ANGELONE":
                    return angelOneClient.getOrders(platformConfig.getAngelone().getApiKey(), sessionToken(a))
                            .map(resp -> Map.<String, Object>of("orders", resp.getOrDefault("data", resp)));
                default:
                    return Mono.just(Map.<String, Object>of("orders", List.of()));
            }
        }).onErrorResume(e -> Mono.just(Map.of("orders", List.of(), "error", friendlyBrokerError("", e.getMessage()), "errorCode", "SESSION_EXPIRED", "action", "RE_LOGIN")));
    }

    // --- Trades ---
    public Mono<Map<String, Object>> getTrades(UUID accountId, UUID userId) {
        return getAccountOwned(accountId, userId).flatMap(a -> {
            if (!a.isSessionActive() || sessionToken(a) == null) {
                Map<String, Object> r = sessionRequiredResponse(a); r.put("trades", List.of()); return Mono.just(r);
            }
            switch (a.getBrokerId()) {
                case "GROWW":
                    return growwClient.listTrades(sessionToken(a), null)
                            .map(resp -> Map.<String, Object>of("trades", resp.getOrDefault("payload", resp)));
                case "ZERODHA":
                    return zerodhaClient.getTrades(platformConfig.getZerodha().getApiKey(), sessionToken(a))
                            .map(resp -> Map.<String, Object>of("trades", resp.getOrDefault("data", resp)));
                case "FYERS":
                    String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + sessionToken(a);
                    return fyersClient.getTrades(fyersAuth)
                            .map(resp -> Map.<String, Object>of("trades", resp.getOrDefault("tradeBook", resp)));
                case "UPSTOX":
                    return upstoxClient.getTrades(sessionToken(a))
                            .map(resp -> Map.<String, Object>of("trades", resp.getOrDefault("data", resp)));
                case "DHAN":
                    return dhanClient.getTrades(sessionToken(a))
                            .map(resp -> Map.<String, Object>of("trades", resp.getOrDefault("trades", resp)))
                            .onErrorResume(e -> Mono.just(Map.of("trades", List.of(), "error", e.getMessage())));
                case "ANGELONE":
                    return angelOneClient.getTrades(platformConfig.getAngelone().getApiKey(), sessionToken(a))
                            .map(resp -> Map.<String, Object>of("trades", resp.getOrDefault("data", resp)));
                default:
                    return Mono.just(Map.<String, Object>of("trades", List.of()));
            }
        }).onErrorResume(e -> Mono.just(Map.of("trades", List.of(), "error", friendlyBrokerError("", e.getMessage()), "errorCode", "SESSION_EXPIRED", "action", "RE_LOGIN")));
    }

    // --- Holdings ---
    public Mono<Map<String, Object>> getHoldings(UUID accountId, UUID userId) {
        return getAccountOwned(accountId, userId).flatMap(a -> {
            if (!a.isSessionActive() || sessionToken(a) == null) {
                Map<String, Object> r = sessionRequiredResponse(a); r.put("holdings", List.of()); return Mono.just(r);
            }
            switch (a.getBrokerId()) {
                case "GROWW":
                    return growwClient.getHoldings(sessionToken(a))
                            .map(resp -> Map.<String, Object>of("holdings", resp.getOrDefault("payload", resp)));
                case "ZERODHA":
                    return zerodhaClient.getHoldings(platformConfig.getZerodha().getApiKey(), sessionToken(a))
                            .map(resp -> Map.<String, Object>of("holdings", resp.getOrDefault("data", resp)));
                case "FYERS":
                    String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + sessionToken(a);
                    return fyersClient.getHoldings(fyersAuth)
                            .map(resp -> Map.<String, Object>of("holdings", resp.getOrDefault("holdings", resp)));
                case "UPSTOX":
                    return upstoxClient.getHoldings(sessionToken(a))
                            .map(resp -> Map.<String, Object>of("holdings", resp.getOrDefault("data", resp)));
                case "DHAN":
                    return dhanClient.getHoldings(sessionToken(a))
                            .map(resp -> Map.<String, Object>of("holdings", resp.getOrDefault("holdings", resp)))
                            .onErrorResume(e -> Mono.just(Map.of("holdings", List.of(), "error", e.getMessage())));
                case "ANGELONE":
                    return angelOneClient.getHoldings(platformConfig.getAngelone().getApiKey(), sessionToken(a))
                            .map(resp -> Map.<String, Object>of("holdings", resp.getOrDefault("data", resp)));
                default:
                    return Mono.just(Map.<String, Object>of("holdings", List.of()));
            }
        }).onErrorResume(e -> Mono.just(Map.of("holdings", List.of(), "error", friendlyBrokerError("", e.getMessage()), "errorCode", "SESSION_EXPIRED", "action", "RE_LOGIN")));
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
                    return growwClient.placeOrder(sessionToken(a), Map.of(
                            "symbol", symbol, "qty", qty, "type", type.equalsIgnoreCase("BUY") ? "BUY" : "SELL",
                            "product", product, "order_type", "MARKET", "price", 0
                    )).map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                case "ZERODHA":
                    return zerodhaClient.placeOrder(platformConfig.getZerodha().getApiKey(), sessionToken(a), Map.of(
                            "tradingsymbol", symbol, "quantity", qty, "transaction_type", type,
                            "product", product, "order_type", "MARKET", "exchange", "NSE"
                    )).map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                case "FYERS": {
                    String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + sessionToken(a);
                    int side = type.equalsIgnoreCase("BUY") ? 1 : -1;
                    return fyersClient.placeOrder(fyersAuth, Map.of(
                            "symbol", symbol, "qty", qty, "side", side,
                            "productType", product, "type", 2, "limitPrice", 0, "stopPrice", 0
                    )).map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                }
                case "UPSTOX":
                    return upstoxClient.placeOrder(sessionToken(a), Map.of(
                            "instrument_token", symbol, "quantity", qty, "transaction_type", type,
                            "product", product, "order_type", "MARKET", "price", 0
                    )).map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                case "DHAN":
                    return dhanClient.placeOrder(sessionToken(a), Map.of(
                            "tradingSymbol", symbol, "quantity", qty, "transactionType", type,
                            "productType", product, "orderType", "MARKET", "exchangeSegment", "NSE_EQ"
                    )).map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                case "ANGELONE": {
                    String apiKey = platformConfig.getAngelone().getApiKey();
                    Map<String, Object> angelBody = new LinkedHashMap<>();
                    angelBody.put("variety", "NORMAL");
                    angelBody.put("tradingsymbol", symbol + "-EQ");
                    angelBody.put("symboltoken", "");
                    angelBody.put("transactiontype", type.equalsIgnoreCase("BUY") ? "BUY" : "SELL");
                    angelBody.put("exchange", "NSE");
                    angelBody.put("ordertype", "MARKET");
                    angelBody.put("producttype", product.equalsIgnoreCase("CNC") ? "DELIVERY" : "INTRADAY");
                    angelBody.put("duration", "DAY");
                    angelBody.put("price", "0");
                    angelBody.put("squareoff", "0");
                    angelBody.put("stoploss", "0");
                    angelBody.put("quantity", String.valueOf(qty));
                    angelBody.put("triggerprice", "0");
                    return angelOneClient.placeOrder(apiKey, sessionToken(a), angelBody)
                            .map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                }
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
                    return growwClient.cancelOrder(sessionToken(a), orderId, "EQ")
                            .map(resp -> Map.<String, Object>of("message", "Order cancelled", "response", resp));
                case "ZERODHA":
                    return zerodhaClient.cancelOrder(platformConfig.getZerodha().getApiKey(), sessionToken(a), orderId)
                            .map(resp -> Map.<String, Object>of("message", "Order cancelled", "response", resp));
                case "FYERS": {
                    String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + sessionToken(a);
                    return fyersClient.cancelOrder(fyersAuth, orderId)
                            .map(resp -> Map.<String, Object>of("message", "Order cancelled", "response", resp));
                }
                case "UPSTOX":
                    return upstoxClient.cancelOrder(sessionToken(a), orderId)
                            .map(resp -> Map.<String, Object>of("message", "Order cancelled", "response", resp));
                case "DHAN":
                    return dhanClient.cancelOrder(sessionToken(a), orderId)
                            .map(resp -> Map.<String, Object>of("message", "Order cancelled", "response", resp));
                case "ANGELONE":
                    return angelOneClient.cancelOrder(platformConfig.getAngelone().getApiKey(), sessionToken(a), "NORMAL", orderId)
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

    private Map<String, Object> parseAngelOneMargin(Map resp) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            Object data = resp.get("data");
            if (data instanceof Map d) {
                double net = toDouble(d.getOrDefault("net", 0));
                double used = toDouble(d.getOrDefault("utiliseddebits", 0));
                double collateral = toDouble(d.getOrDefault("collateral", 0));
                r.put("availableMargin", net - used);
                r.put("usedMargin", used);
                r.put("totalFunds", net);
                r.put("collateral", collateral);
                return r;
            }
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
                    if (!a.isSessionActive() || sessionToken(a) == null) {
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
                                if (marginResponseHasError(margin)) {
                                    Map<String, Object> r = buildSignal(a, 1, "error",
                                            String.valueOf(margin.getOrDefault("error", "Broker API error")));
                                    r.put("errorCode", margin.get("errorCode"));
                                    r.put("action", margin.get("action"));
                                    return r;
                                }
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

    public Mono<Map<String, Object>> getBrokerProfile(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")))
                .flatMap(this::getBrokerProfile);
    }

    private Mono<Map<String, Object>> getBrokerProfile(BrokerAccount a) {
        switch (a.getBrokerId()) {
            case "GROWW":
                return growwClient.getProfile(sessionToken(a))
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
                return zerodhaClient.getProfile(platformConfig.getZerodha().getApiKey(), sessionToken(a))
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
                String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + sessionToken(a);
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
                return upstoxClient.getProfile(sessionToken(a))
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
                return dhanClient.getProfile(sessionToken(a))
                        .map(resp -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("name", resp.getOrDefault("userName", resp.getOrDefault("name", "")));
                            p.put("email", resp.getOrDefault("emailId", resp.getOrDefault("email", "")));
                            p.put("clientId", resp.getOrDefault("dhanClientId", resp.getOrDefault("clientId", "")));
                            p.put("broker", "Dhan");
                            return p;
                        });
            case "ANGELONE":
                return angelOneClient.getProfile(platformConfig.getAngelone().getApiKey(), sessionToken(a))
                        .map(resp -> {
                            Map<String, Object> p = new LinkedHashMap<>();
                            Object data = resp.get("data");
                            if (data instanceof Map d) {
                                p.put("name", d.getOrDefault("name", ""));
                                p.put("email", d.getOrDefault("email", ""));
                                p.put("clientId", d.getOrDefault("clientcode", ""));
                                p.put("broker", "Angel One");
                                p.put("exchanges", d.getOrDefault("exchanges", List.of()));
                                p.put("products", d.getOrDefault("products", List.of()));
                            } else {
                                p.put("raw", resp);
                                p.put("broker", "Angel One");
                            }
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
                .filter(a -> a.isSessionActive() && sessionToken(a) != null)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active broker session. Login first.")));
    }

    /** Same as getActiveAccount but returns account even if session is inactive (for graceful error responses) */
    private Mono<BrokerAccount> getAccountOwned(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")));
    }

    private Map<String, Object> sessionRequiredResponse(BrokerAccount a) {
        Map<String, Object> r = new LinkedHashMap<>();
        String reason = describeInactiveReason(a);
        r.put("error", "Session expired. Please re-login to " + BrokerAccountDto.from(a).getBrokerName() + " to continue.");
        r.put("reason", reason);
        r.put("errorCode", "SESSION_EXPIRED");
        r.put("action", "RE_LOGIN");
        r.put("accountId", a.getId().toString());
        r.put("brokerId", a.getBrokerId());
        r.put("brokerName", BrokerAccountDto.from(a).getBrokerName());
        r.put("status", a.getStatus());
        r.put("sessionActive", false);
        log.info("BROKER_SESSION_REQUIRED accountId={} broker={} reason={}", a.getId(), a.getBrokerId(), reason);
        return r;
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
        return loginConfigForBroker("GROWW");
    }

    /** Shared login UI config — used by GET /brokers and reconnect after disconnect (all brokers). */
    private Map<String, Object> loginConfigForBroker(String brokerId) {
        String b = brokerId != null ? brokerId.toUpperCase() : "";
        return switch (b) {
            case "GROWW" -> {
                Map<String, Object> m = brokerShell("GROWW", "Groww", "token", null);
                m.put("loginOptions", List.of(
                        loginOption("accessToken",
                                "Paste access token from Groww settings (no API key needed)",
                                List.of("accessToken"),
                                "PUT /api/v1/brokers/accounts/{accountId}/token"),
                        loginOption("apiKeyWithTotp",
                                "API key + TOTP code from authenticator app",
                                List.of("apiKey", "totpCode"),
                                "PUT /api/v1/brokers/accounts/{accountId} then POST .../login")));
                m.put("note", "Groww requires per-user credentials. Each user generates their own from Groww settings.");
                yield m;
            }
            case "DHAN" -> {
                Map<String, Object> m = brokerShell("DHAN", "Dhan", "oauth", "tokenId");
                m.put("loginOptions", List.of(
                        loginOption("accessToken",
                                "Paste access token from Dhan Web (Profile → DhanHQ Trading APIs)",
                                List.of("accessToken"),
                                "PUT /api/v1/brokers/accounts/{accountId}/token"),
                        loginOption("oauth",
                                "Login via Dhan in browser (Connect button)",
                                List.of(),
                                "POST /api/v1/brokers/accounts/{accountId}/login {} then again with authCode")));
                yield m;
            }
            case "ZERODHA" -> {
                Map<String, Object> m = brokerShell("ZERODHA", "Zerodha", "oauth", "requestToken");
                m.put("loginOptions", List.of(
                        loginOption("oauth",
                                "Login with Zerodha Kite in browser",
                                List.of(),
                                "GET .../oauth-url → open popup → POST .../login { requestToken }")));
                yield m;
            }
            case "FYERS" -> {
                Map<String, Object> m = brokerShell("FYERS", "Fyers", "oauth", "authCode");
                m.put("loginOptions", List.of(
                        loginOption("oauth",
                                "Login with Fyers in browser",
                                List.of(),
                                "GET .../oauth-url → open popup → POST .../login { authCode }")));
                yield m;
            }
            case "UPSTOX" -> {
                Map<String, Object> m = brokerShell("UPSTOX", "Upstox", "oauth", "authCode");
                m.put("loginOptions", List.of(
                        loginOption("oauth",
                                "Login with Upstox in browser",
                                List.of(),
                                "GET .../oauth-url → open popup → POST .../login { authCode }")));
                yield m;
            }
            case "ANGELONE" -> {
                Map<String, Object> m = brokerShell("ANGELONE", "Angel One", "totp", "totpCode");
                m.put("loginOptions", List.of(
                        loginOption("totp",
                                "Angel One client code + password + TOTP from authenticator",
                                List.of("clientId", "apiSecret", "totpCode"),
                                "PUT /api/v1/brokers/accounts/{accountId} then POST .../login { totpCode }")));
                yield m;
            }
            default -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("brokerId", b);
                m.put("loginMethod", "mock");
                m.put("loginOptions", List.of());
                yield m;
            }
        };
    }

    private Map<String, Object> loginConfigForAccount(BrokerAccount a, String redirectUri) {
        Map<String, Object> config = new LinkedHashMap<>(loginConfigForBroker(a.getBrokerId()));
        config.put("accountId", a.getId().toString());
        config.put("status", a.getStatus());
        config.put("sessionActive", a.isSessionActive());
        config.put("requiresReconnect", !a.isSessionActive() || sessionToken(a) == null);
        config.put("loginOptionsUrl", "/api/v1/brokers/accounts/" + a.getId() + "/login-options");
        List<String> methods = extractLoginOptionMethods(config.get("loginOptions"));
        config.put("loginOptionMethods", methods);
        enrichOAuthUrls(a, config, redirectUri);
        return config;
    }

    private Map<String, Object> buildDisconnectResponse(BrokerAccount a) {
        Map<String, Object> r = loginConfigForAccount(a, null);
        r.put("message", "Broker disconnected. Choose a login method to reconnect.");
        r.put("notificationType", "BROKER_DISCONNECTED");
        r.put("reason", "USER_DISCONNECT");
        return r;
    }

    private void clearBrokerSession(BrokerAccount a) {
        a.setSessionActive(false);
        a.setAccessToken(null);
        a.setSessionExpires(null);
        a.setStatus("AUTH_REQUIRED");
    }

    /** Disconnect: keep Groww API key so reconnect can use TOTP-only login. */
    private void clearStoredCredentialsOnDisconnect(BrokerAccount a) {
        if (!"GROWW".equals(a.getBrokerId())) {
            clearStoredCredentials(a);
        }
    }

    private void clearStoredCredentials(BrokerAccount a) {
        String b = a.getBrokerId();
        if ("GROWW".equals(b) || "ANGELONE".equals(b) || "DHAN".equals(b)) {
            a.setApiKey("");
            a.setApiSecret("");
        }
        if ("ANGELONE".equals(b) || "DHAN".equals(b)) {
            a.setClientId("");
        }
    }

    private Mono<Void> notifyBrokerReconnect(UUID userId, BrokerAccount a, String type, String title, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", a.getId().toString());
        data.put("brokerId", a.getBrokerId());
        data.put("brokerName", BrokerAccountDto.from(a).getBrokerName());
        data.put("action", "RECONNECT");
        data.put("loginOptionsUrl", "/api/v1/brokers/accounts/" + a.getId() + "/login-options");
        return notifications.push(userId, title, message, type, data)
                .doOnSuccess(n -> log.info("BROKER_NOTIFICATION type={} accountId={} userId={} broker={}",
                        type, a.getId(), userId, a.getBrokerId()))
                .then();
    }

    private void logLoginOptions(UUID accountId, UUID userId, BrokerAccount a, Map<String, Object> config, String source) {
        log.info("BROKER_LOGIN_OPTIONS source={} accountId={} userId={} broker={} sessionActive={} requiresReconnect={} options={}",
                source, accountId, userId, a.getBrokerId(), a.isSessionActive(),
                config.get("requiresReconnect"), config.get("loginOptionMethods"));
    }

    private void logSessionStatus(UUID accountId, UUID userId, Map<String, Object> status) {
        log.info("BROKER_SESSION_STATUS accountId={} userId={} broker={} sessionActive={} health={} errorCode={} reason={} action={} options={}",
                accountId, userId, status.get("broker"), status.get("sessionActive"),
                status.get("connectionHealth"), status.getOrDefault("errorCode", "-"),
                status.getOrDefault("reason", "-"), status.getOrDefault("action", "-"),
                status.get("loginOptionMethods"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractLoginOptionMethods(Object loginOptions) {
        if (!(loginOptions instanceof List<?> list)) {
            return List.of();
        }
        List<String> methods = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m && m.get("method") != null) {
                methods.add(String.valueOf(m.get("method")));
            }
        }
        return methods;
    }

    private static String describeInactiveReason(BrokerAccount a) {
        if (!a.isSessionActive()) {
            return "SESSION_INACTIVE";
        }
        if (a.getSessionExpires() != null && a.getSessionExpires().isBefore(Instant.now())) {
            return "TOKEN_EXPIRED";
        }
        return "NO_ACCESS_TOKEN";
    }

    private static String detectLoginMethod(BrokerAccount a, BrokerLoginRequest req) {
        if (req == null) {
            return "DHAN".equals(a.getBrokerId()) ? "oauth_consent" : "default";
        }
        if (req.getTotpCode() != null && !req.getTotpCode().isBlank()) {
            return "apiKeyWithTotp";
        }
        if (req.getRequestToken() != null && !req.getRequestToken().isBlank()) {
            return "oauth";
        }
        if (req.getAuthCode() != null && !req.getAuthCode().isBlank()) {
            return "DHAN".equals(a.getBrokerId()) ? "oauth" : "oauth";
        }
        return "default";
    }

    private void enrichOAuthUrls(BrokerAccount a, Map<String, Object> config, String redirectUri) {
        String redirect = (redirectUri != null && !redirectUri.isBlank())
                ? redirectUri
                : platformConfig.getCallbackUrl();
        switch (a.getBrokerId()) {
            case "ZERODHA" -> {
                var creds = platformConfig.getZerodha();
                if (creds != null && creds.getApiKey() != null) {
                    config.put("oauthUrl", "https://kite.zerodha.com/connect/login?v=3&api_key=" + creds.getApiKey());
                }
                config.put("message", "Open oauthUrl in browser, then POST login with requestToken from callback.");
            }
            case "FYERS" -> {
                var creds = platformConfig.getFyers();
                if (creds != null && creds.getApiKey() != null) {
                    config.put("oauthUrl", "https://api-t1.fyers.in/api/v3/generate-authcode?client_id="
                            + creds.getApiKey() + "&redirect_uri=" + redirect + "&response_type=code&state=ok");
                }
                config.put("message", "Open oauthUrl in browser, then POST login with authCode from callback.");
            }
            case "UPSTOX" -> {
                var creds = platformConfig.getUpstox();
                if (creds != null && creds.getApiKey() != null) {
                    config.put("oauthUrl", "https://api.upstox.com/v2/login/authorization/dialog?response_type=code&client_id="
                            + creds.getApiKey() + "&redirect_uri=" + redirect);
                }
                config.put("message", "Open oauthUrl in browser, then POST login with authCode from callback.");
            }
            case "DHAN" ->
                    config.put("message", "Option 1: PUT token. Option 2: POST login {} for loginUrl, then login with authCode (tokenId).");
            case "GROWW" ->
                    config.put("message", "Choose access token or API key + TOTP from loginOptions.");
            case "ANGELONE" ->
                    config.put("message", "Set clientId and apiSecret (password) on account, then POST login with totpCode.");
            default -> { }
        }
    }

    private static Map<String, Object> brokerShell(String id, String name, String loginMethod, String loginField) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("brokerId", id);
        m.put("name", name);
        m.put("isActive", true);
        m.put("loginMethod", loginMethod);
        m.put("requiredFields", List.of());
        if (loginField != null) {
            m.put("loginField", loginField);
        }
        return m;
    }

    private static Map<String, Object> loginOption(String method, String description, List<String> requiredFields, String api) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("method", method);
        o.put("description", description);
        o.put("requiredFields", requiredFields);
        o.put("api", api);
        return o;
    }

    private Map<String, Object> brokerInfo(String id, String name, boolean active, List<String> fields, String loginMethod, String loginField) {
        Map<String, Object> m = brokerShell(id, name, loginMethod, loginField);
        m.put("requiredFields", fields);
        m.put("isActive", active);
        return m;
    }

    private static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception e) { return 0; }
    }

    private static boolean isAuthError(Throwable e) {
        String msg = e != null ? e.getMessage() : null;
        return msg != null && (msg.contains("401") || msg.contains("403")
                || msg.contains("Unauthorized") || msg.contains("Invalid"));
    }

    private static String friendlyBrokerError(String broker, String rawError) {
        String name = switch (broker) {
            case "GROWW" -> "Groww";
            case "ZERODHA" -> "Zerodha";
            case "FYERS" -> "Fyers";
            case "UPSTOX" -> "Upstox";
            case "DHAN" -> "Dhan";
            default -> broker;
        };
        if (rawError != null && (rawError.contains("401") || rawError.contains("Unauthorized") || rawError.contains("Invalid"))) {
            return "Session expired. Please re-login to " + name + " to continue.";
        }
        if (rawError != null && rawError.contains("403")) {
            return name + " access denied. Please check your API permissions.";
        }
        if (rawError != null && (rawError.contains("timeout") || rawError.contains("Timeout"))) {
            return name + " is not responding. Please try again.";
        }
        return name + " error. Please re-login and try again.";
    }
}
