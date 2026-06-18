package com.copytrading.broker;

import com.copytrading.broker.dhan.DhanApiClient;
import com.copytrading.broker.dto.*;
import com.copytrading.broker.fyers.FyersApiClient;
import com.copytrading.broker.groww.GrowwApiClient;
import com.copytrading.broker.proxy.ProxyHttpClient;
import com.copytrading.broker.upstox.UpstoxApiClient;
import com.copytrading.broker.zerodha.ZerodhaApiClient;
import com.copytrading.broker.angelone.AngelOneApiClient;
import com.copytrading.engine.BrokerFieldTranslator;
import com.copytrading.engine.InstrumentCache;
import com.copytrading.engine.SymbolMapper;
import com.copytrading.master.MasterActiveAccountRepository;
import com.copytrading.notification.NotificationService;
import com.copytrading.security.BrokerCredentials;
import com.copytrading.subscription.SubscriptionRepository;
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

    /** Prevent frontend from retrying the same auth code (single-use, expires in 2 min). */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> usedAuthCodes = new java.util.concurrent.ConcurrentHashMap<>();

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
    private final SymbolMapper symbolMapper;
    private final InstrumentCache instruments;
    private final SubscriptionRepository subscriptionRepo;
    private final MasterActiveAccountRepository masterActiveAccountRepo;
    private final com.copytrading.broker.groww.GrowwProxyRouter proxyRouter;
    private final ProxyHttpClient proxyHttpClient;

    public BrokerAccountService(BrokerAccountRepository repo, GrowwApiClient growwClient,
                                ZerodhaApiClient zerodhaClient, FyersApiClient fyersClient,
                                UpstoxApiClient upstoxClient, DhanApiClient dhanClient,
                                AngelOneApiClient angelOneClient,
                                PlatformBrokerConfig platformConfig,
                                BrokerCredentials credentials,
                                NotificationService notifications,
                                SymbolMapper symbolMapper,
                                InstrumentCache instruments,
                                SubscriptionRepository subscriptionRepo,
                                MasterActiveAccountRepository masterActiveAccountRepo,
                                com.copytrading.broker.groww.GrowwProxyRouter proxyRouter,
                                ProxyHttpClient proxyHttpClient) {
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
        this.symbolMapper = symbolMapper;
        this.instruments = instruments;
        this.subscriptionRepo = subscriptionRepo;
        this.masterActiveAccountRepo = masterActiveAccountRepo;
        this.proxyRouter = proxyRouter;
        this.proxyHttpClient = proxyHttpClient;
    }

    private String sessionToken(BrokerAccount a) {
        return credentials.accessToken(a);
    }

    /** Get Zerodha API key for a specific account (per-user credentials, fallback to platform). */
    private String zerodhaApiKey(BrokerAccount a) {
        if (a.getApiKey() != null && !a.getApiKey().isBlank()) return a.getApiKey();
        var creds = platformConfig.getZerodha();
        return creds != null ? creds.getApiKey() : null;
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
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("brokers", brokers);
        if (platformConfig.getServerEgressIp() != null) {
            resp.put("platformServerIp", platformConfig.getServerEgressIp());
            resp.put("ipWhitelistNote", "Whitelist platformServerIp in Groww API dashboard (required for API access)");
        }
        return Mono.just(resp);
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

        // Per-user proxy settings (optional)
        if (req.getProxyHost() != null) a.setProxyHost(req.getProxyHost());
        if (req.getProxyPort() != null) a.setProxyPort(req.getProxyPort());
        if (req.getProxyUser() != null) a.setProxyUser(req.getProxyUser());
        if (req.getProxyPass() != null) a.setProxyPass(req.getProxyPass());

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

        // Auto-assign ip_slot for Groww (each Groww user needs a unique IP)
        if ("GROWW".equals(broker)) {
            return repo.findMaxGrowwIpSlot()
                    .defaultIfEmpty(-1)
                    .flatMap(maxSlot -> {
                        int nextSlot = maxSlot + 1;
                        a.setIpSlot(nextSlot);
                        return repo.save(a).map(saved -> {
                            String assignedIp = proxyRouter.getPublicIp(nextSlot);
                            log.info("BROKER_LINKED id={} user={} broker=GROWW ipSlot={} assignedIp={}",
                                    saved.getId(), userId, nextSlot, assignedIp);
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("accountId", saved.getId());
                            r.put("brokerId", saved.getBrokerId());
                            r.put("status", saved.getStatus());
                            r.put("ipSlot", nextSlot);
                            r.put("assignedIp", assignedIp);
                            r.put("whitelistIp", assignedIp);
                            r.put("message", "Whitelist IP " + assignedIp + " in your Groww API dashboard before connecting.");
                            return r;
                        });
                    });
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

    // 3.3 List user's accounts (enriched with live margin/pnl/positions)
    public Mono<Map<String, Object>> listAccounts(UUID userId) {
        return repo.findByUserId(userId)
                .flatMap(a -> {
                    BrokerAccountDto dto = BrokerAccountDto.from(a);
                    if (!a.isSessionActive() || a.getAccessToken() == null) {
                        return Mono.just(dto);
                    }
                    // Enrich with live margin data
                    return getMargin(a.getId(), userId)
                            .map(margin -> {
                                double avail = toDouble(margin.get("availableMargin"));
                                double used = toDouble(margin.get("usedMargin"));
                                double total = toDouble(margin.get("totalFunds"));
                                dto.setMargin(avail);
                                dto.setAvailableMargin(avail);
                                dto.setUsedMargin(used);
                                dto.setTotalFunds(total > 0 ? total : avail + used);
                                dto.setPnl(toDouble(margin.get("totalPnl")));
                                return dto;
                            })
                            .onErrorReturn(dto)
                            .defaultIfEmpty(dto);
                }, 2)
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
                    // Proxy settings
                    boolean proxyChanged = false;
                    if (req.getProxyHost() != null) { a.setProxyHost(req.getProxyHost()); proxyChanged = true; }
                    if (req.getProxyPort() != null) { a.setProxyPort(req.getProxyPort()); proxyChanged = true; }
                    if (req.getProxyUser() != null) { a.setProxyUser(req.getProxyUser()); proxyChanged = true; }
                    if (req.getProxyPass() != null) { a.setProxyPass(req.getProxyPass()); proxyChanged = true; }
                    if (proxyChanged) {
                        proxyHttpClient.evict(accountId);
                        log.info("PROXY_UPDATED accountId={} host={} port={}", accountId, a.getProxyHost(), a.getProxyPort());
                    }
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
                    // After clearing session (accessToken=null), only encrypt fields that were
                    // freshly set to plaintext. For Groww, apiSecret stays encrypted in DB — don't touch it.
                    // clearStoredCredentials sets apiKey/apiSecret to "" for non-Groww brokers, safe to encrypt.
                    if (!"GROWW".equals(a.getBrokerId())) {
                        credentials.encryptSensitiveFields(a);
                    }
                    // For Groww, nothing needs encryption here — accessToken is null, apiSecret already encrypted
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
                    credentials.encryptAccessTokenOnly(a);
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
                .flatMap(a -> {
                    log.info("BROKER_DELETE_START accountId={} userId={} broker={}", accountId, userId, a.getBrokerId());
                    // Clear FK references before deleting:
                    // 1. Null out broker_account_id on any subscriptions using this account
                    // 2. Remove master_active_accounts row if it references this account
                    return subscriptionRepo.clearBrokerAccountId(accountId)
                            .then(masterActiveAccountRepo.deleteById(userId).onErrorResume(e -> Mono.empty()))
                            .then(repo.delete(a))
                            .thenReturn(Map.of("message", "Account unlinked"))
                            .doOnSuccess(r -> log.info("BROKER_DELETE_OK accountId={} userId={} broker={}",
                                    accountId, userId, a.getBrokerId()));
                });
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
        String apiKey = a.getApiKey();
        String apiSecret = credentials.apiSecret(a);
        var platformCreds = platformConfig.getGroww();
        if ((apiKey == null || apiKey.isBlank()) && platformCreds != null && platformCreds.getApiKey() != null && !platformCreds.getApiKey().isBlank()) {
            apiKey = platformCreds.getApiKey();
            if (apiSecret == null || apiSecret.isBlank()) {
                apiSecret = platformCreds.getApiSecret();
            }
        }
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No Groww API key configured. Set apiKey on the broker account or configure platform-level Groww credentials."));
        }

        Mono<Map> tokenMono;
        if (req != null && req.getTotpCode() != null && !req.getTotpCode().isBlank()) {
            tokenMono = growwClient.generateToken(apiKey, req.getTotpCode());
        } else if (apiSecret != null && !apiSecret.isBlank()) {
            tokenMono = growwClient.generateTokenWithSecret(apiKey, apiSecret);
        } else {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "totpCode required for Groww login. After disconnect, use API key + TOTP: POST .../login { \"totpCode\": \"123456\" }"));
        }
        return tokenMono.flatMap(resp -> extractAndSaveSession(a, resp, "Groww",
                r -> {
                    if (r.containsKey("payload") && r.get("payload") instanceof Map p) return (String) p.get("token");
                    return (String) r.get("token");
                }))
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    String msg = e.getMessage() != null ? e.getMessage() : "unknown";
                    log.error("GROWW_LOGIN_FAILED error={}", msg, e);
                    if (msg.toLowerCase().contains("ip") || msg.toLowerCase().contains("whitelist")
                            || msg.toLowerCase().contains("forbidden")) {
                        String ip = platformConfig.getServerEgressIp();
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                                "Groww IP whitelist error. Add platform IP " + (ip != null ? ip : "(see GET /api/v1/brokers)")
                                        + " in your Groww API dashboard, then retry with totpCode. Detail: " + msg));
                    }
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Groww API error: " + msg));
                });
    }

    // --- ZERODHA LOGIN ---
    private Mono<Map<String, Object>> loginZerodha(BrokerAccount a, BrokerLoginRequest req) {
        // Per-user Zerodha: each user has their own api_key + api_secret from their Kite Connect app
        String apiKey = a.getApiKey();
        String apiSecret = credentials.apiSecret(a); // decrypt stored secret
        // Fallback to platform-level if user hasn't set their own
        if (apiKey == null || apiKey.isBlank()) apiKey = platformConfig.getZerodha().getApiKey();
        if (apiSecret == null || apiSecret.isBlank()) apiSecret = platformConfig.getZerodha().getApiSecret();
        
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Zerodha API key and secret are required. Go to developers.kite.trade, create an app, and update your broker account with apiKey + apiSecret."));
        }
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
        // Per-user Upstox: support user's own api_key/api_secret, fallback to platform
        String apiKey = a.getApiKey();
        String apiSecret = credentials.apiSecret(a);
        var platformCreds = platformConfig.getUpstox();
        if (apiKey == null || apiKey.isBlank()) apiKey = platformCreds.getApiKey();
        if (apiSecret == null || apiSecret.isBlank()) apiSecret = platformCreds.getApiSecret();

        if (req == null || req.getAuthCode() == null || req.getAuthCode().isBlank()) {
            String loginUrl = "https://api.upstox.com/v2/login/authorization/dialog?response_type=code&client_id=" + apiKey + "&redirect_uri=" + platformConfig.getCallbackUrl();
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "authCode required. Open this URL to login: " + loginUrl));
        }
        // Upstox auth codes are single-use at the broker API level — no need for backend dedup.
        // Removed local dedup to allow retries after failed attempts (network errors, etc.).
        return upstoxClient.generateToken(apiKey, apiSecret, req.getAuthCode(), platformConfig.getCallbackUrl())
                .flatMap(resp -> extractAndSaveSession(a, resp, "Upstox",
                        r -> (String) r.get("access_token")))
                .onErrorResume(e -> {
                    if (e instanceof ResponseStatusException) return Mono.error(e);
                    log.error("UPSTOX_LOGIN_FAILED error={}", e.getMessage(), e);
                    String msg = e.getMessage() != null ? e.getMessage() : "unknown";
                    if (msg.contains("401") || msg.contains("Invalid Credentials") || msg.contains("UDAPI100016")) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Upstox auth code expired or already used. Please open the Upstox login page again to get a fresh code."));
                    }
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upstox API error: " + msg));
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
        // clientId = Angel One client code, apiSecret = password (stored on account — must decrypt)
        String clientCode = a.getClientId();
        String password = credentials.apiSecret(a);  // decrypt stored password
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
        // Only encrypt the new access token — apiSecret/proxyPass are already encrypted in DB
        credentials.encryptAccessTokenOnly(a);
        return repo.save(a).flatMap(s -> {
            // Auto-migrate subscriptions: if user has other inactive accounts of same broker type,
            // move all subscriptions to this newly active account
            return subscriptionRepo.migrateToActiveAccount(s.getUserId(), s.getBrokerId(), s.getId())
                    .doOnNext(count -> {
                        if (count > 0) log.info("SUBSCRIPTION_MIGRATED userId={} broker={} toAccount={} count={}",
                                s.getUserId(), s.getBrokerId(), s.getId(), count);
                    })
                    .onErrorResume(e -> { log.warn("SUBSCRIPTION_MIGRATE_FAILED: {}", e.getMessage()); return Mono.just(0); })
                    .thenReturn(s);
        }).map(s -> {
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
                    result = zerodhaClient.getMargins(zerodhaApiKey(a), sessionToken(a)).map(resp -> parseZerodhaMargin(resp)); break;
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
                    // Grace period: don't nuke session if it was activated less than 2 minutes ago
                    // (prevents race condition where status check kills a freshly logged-in session)
                    boolean withinGrace = a.getSessionExpires() != null
                            && a.getSessionExpires().minusSeconds(86400 - 120).isAfter(Instant.now());
                    if (withinGrace) {
                        log.warn("AUTH_ERROR_WITHIN_GRACE_PERIOD accountId={} broker={} — skipping session nuke",
                                accountId, a.getBrokerId());
                        fallback.remove("action");
                        fallback.put("errorCode", "TRANSIENT_ERROR");
                        fallback.put("action", "RETRY");
                        return Mono.just(fallback);
                    }
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
                    return zerodhaClient.getPositions(zerodhaApiKey(a), sessionToken(a))
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
                            .map(resp -> Map.<String, Object>of(
                                    "positions", resp.getOrDefault("positions", List.of())));
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
                    return zerodhaClient.getOrders(zerodhaApiKey(a), sessionToken(a))
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
                    return zerodhaClient.getTrades(zerodhaApiKey(a), sessionToken(a))
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
                    return zerodhaClient.getHoldings(zerodhaApiKey(a), sessionToken(a))
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
            String symbol = bodyField(body, "symbol");
            if (symbol == null || symbol.isBlank()) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbol is required"));
            }
            int qty = bodyInt(body, "qty", "quantity");
            if (qty <= 0) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "qty must be > 0"));
            }
            String type = bodyField(body, "type", "side", "transaction_type");
            if (type == null || type.isBlank()) type = "SELL";
            String product = bodyField(body, "product", "productType");
            if (product == null || product.isBlank()) product = "MIS";
            String exchange = bodyField(body, "exchange");
            if (exchange == null || exchange.isBlank()) exchange = "NSE";
            boolean isFnO = symbolMapper.isFnO(symbol)
                    || Boolean.TRUE.equals(body.get("isFnO"))
                    || "FNO".equalsIgnoreCase(String.valueOf(body.getOrDefault("segment", "")));

            switch (a.getBrokerId()) {
                case "GROWW": {
                    Map<String, Object> order = new LinkedHashMap<>();
                    order.put("trading_symbol", symbol);
                    order.put("quantity", qty);
                    order.put("transaction_type", type.equalsIgnoreCase("BUY") ? "BUY" : "SELL");
                    order.put("order_type", "MARKET");
                    order.put("price", 0);
                    order.put("trigger_price", 0);
                    order.put("exchange", BrokerFieldTranslator.exchangeSegment(exchange, "GROWW", isFnO));
                    order.put("segment", isFnO ? "FNO" : "CASH");
                    order.put("product", BrokerFieldTranslator.product(product, "GROWW", isFnO));
                    order.put(BrokerFieldTranslator.validityFieldName("GROWW"), BrokerFieldTranslator.validityValue("GROWW"));
                    return growwClient.placeOrder(sessionToken(a), order)
                            .map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                }
                case "ZERODHA":
                    return zerodhaClient.placeOrder(zerodhaApiKey(a), sessionToken(a), Map.of(
                            "tradingsymbol", symbol, "quantity", qty, "transaction_type", type,
                            "product", BrokerFieldTranslator.product(product, "ZERODHA", isFnO),
                            "order_type", "MARKET",
                            "exchange", BrokerFieldTranslator.exchangeSegment(exchange, "ZERODHA", isFnO)
                    )).map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                case "FYERS": {
                    String fyersAuth = platformConfig.getFyers().getApiKey() + ":" + sessionToken(a);
                    int side = type.equalsIgnoreCase("BUY") ? 1 : -1;
                    String fyersSym = symbol.contains(":") ? symbol : ("NSE:" + symbol + (isFnO ? "" : "-EQ"));
                    return fyersClient.placeOrder(fyersAuth, Map.of(
                            "symbol", fyersSym, "qty", qty, "side", side,
                            "productType", BrokerFieldTranslator.product(product, "FYERS", isFnO),
                            "type", 2, "limitPrice", 0, "stopPrice", 0
                    )).map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                }
                case "UPSTOX": {
                    String instrumentToken = bodyField(body, "instrument_token", "instrumentToken");
                    if (instrumentToken == null || instrumentToken.isBlank()) {
                        instrumentToken = instruments.getUpstoxInstrumentKey(symbol, isFnO);
                    }
                    if (instrumentToken == null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Upstox instrument not found for " + symbol + ". Ensure F&O symbol is correct."));
                    }
                    Map<String, Object> upOrder = new LinkedHashMap<>();
                    upOrder.put("instrument_token", instrumentToken);
                    upOrder.put("quantity", qty);
                    upOrder.put("transaction_type", type.equalsIgnoreCase("BUY") ? "BUY" : "SELL");
                    upOrder.put("product", BrokerFieldTranslator.product(product, "UPSTOX", isFnO));
                    upOrder.put("order_type", "MARKET");
                    upOrder.put("price", 0);
                    upOrder.put("market_protection", -1);
                    return upstoxClient.placeOrder(sessionToken(a), upOrder)
                            .map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                }
                case "DHAN": {
                    String seg = BrokerFieldTranslator.exchangeSegment(exchange, "DHAN", isFnO);
                    String secId = instruments.getDhanSecurityId(symbol, isFnO);
                    Map<String, Object> dhanOrder = new LinkedHashMap<>();
                    dhanOrder.put("dhanClientId", a.getClientId() != null ? a.getClientId() : "");
                    dhanOrder.put("transactionType", type.equalsIgnoreCase("BUY") ? "BUY" : "SELL");
                    dhanOrder.put("exchangeSegment", seg);
                    dhanOrder.put("productType", BrokerFieldTranslator.product(product, "DHAN", isFnO));
                    dhanOrder.put("orderType", "MARKET");
                    dhanOrder.put("quantity", qty);
                    dhanOrder.put("price", 0);
                    if (secId != null) dhanOrder.put("securityId", secId);
                    else dhanOrder.put("tradingSymbol", symbol);
                    return dhanClient.placeOrder(sessionToken(a), dhanOrder)
                            .map(resp -> Map.<String, Object>of("message", "Position close order placed", "response", resp));
                }
                case "ANGELONE": {
                    String apiKey = platformConfig.getAngelone().getApiKey();
                    String angelSym = isFnO ? symbol : symbol + "-EQ";
                    String angelToken = instruments.getAngelToken(angelSym, isFnO);
                    Map<String, Object> angelBody = new LinkedHashMap<>();
                    angelBody.put("variety", "NORMAL");
                    angelBody.put("tradingsymbol", angelSym);
                    angelBody.put("symboltoken", angelToken != null ? angelToken : "");
                    angelBody.put("transactiontype", type.equalsIgnoreCase("BUY") ? "BUY" : "SELL");
                    angelBody.put("exchange", BrokerFieldTranslator.exchangeSegment(exchange, "ANGELONE", isFnO));
                    angelBody.put("ordertype", "MARKET");
                    angelBody.put("producttype", BrokerFieldTranslator.product(product, "ANGELONE", isFnO));
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

    private static String bodyField(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object v = body.get(key);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
        }
        return null;
    }

    private static int bodyInt(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object v = body.get(key);
            if (v instanceof Number n) return n.intValue();
            if (v != null) {
                try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) { /* try next */ }
            }
        }
        return 0;
    }

    // --- Cancel Order ---
    public Mono<Map<String, Object>> cancelOrder(UUID accountId, UUID userId, String orderId) {
        return getActiveAccount(accountId, userId).flatMap(a -> {
            switch (a.getBrokerId()) {
                case "GROWW":
                    return growwClient.cancelOrder(sessionToken(a), orderId, "EQ")
                            .map(resp -> Map.<String, Object>of("message", "Order cancelled", "response", resp));
                case "ZERODHA":
                    return zerodhaClient.cancelOrder(zerodhaApiKey(a), sessionToken(a), orderId)
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
                Object util = eq.get("utilised");
                double net = toDouble(eq.getOrDefault("net", 0));
                double debits = util instanceof Map u ? toDouble(u.getOrDefault("debits", 0)) : 0;
                r.put("availableMargin", net);
                r.put("usedMargin", debits);
                r.put("totalFunds", net + debits);
                Object avail = eq.get("available");
                r.put("collateral", avail instanceof Map a2 ? toDouble(a2.getOrDefault("collateral", 0)) : 0);
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
                return zerodhaClient.getProfile(zerodhaApiKey(a), sessionToken(a))
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
                m.put("requiresIpWhitelist", true);
                if (platformConfig.getServerEgressIp() != null) {
                    m.put("platformServerIp", platformConfig.getServerEgressIp());
                }
                m.put("loginOptions", List.of(
                        loginOption("accessToken",
                                "Paste access token from Groww settings (no API key needed)",
                                List.of("accessToken"),
                                "PUT /api/v1/brokers/accounts/{accountId}/token"),
                        loginOption("apiKeyWithTotp",
                                "API key + TOTP code from authenticator app (recommended after disconnect)",
                                List.of("apiKey", "totpCode"),
                                "POST /api/v1/brokers/accounts/{accountId}/login { totpCode }")));
                m.put("note", "Groww requires whitelisting platformServerIp in your Groww API dashboard.");
                yield m;
            }
            case "DHAN" -> {
                Map<String, Object> m = brokerShell("DHAN", "Dhan", "oauth", "tokenId");
                m.put("optionalFields", List.of(
                        Map.of("field", "clientId", "label", "Dhan Client ID",
                                "hint", "Your Dhan client ID (saved from first login, only needed if changed)")));
                m.put("loginOptions", List.of(
                        loginOption("accessToken",
                                "Paste access token from Dhan Web (Profile → DhanHQ Trading APIs)",
                                List.of("accessToken"),
                                "PUT /api/v1/brokers/accounts/{accountId}/token"),
                        loginOption("oauth",
                                "Login via Dhan in browser (Connect button)",
                                List.of("clientId"),
                                "POST /api/v1/brokers/accounts/{accountId}/login { clientId } then again with authCode")));
                yield m;
            }
            case "ZERODHA" -> {
                Map<String, Object> m = brokerShell("ZERODHA", "Zerodha", "oauth", "requestToken");
                m.put("loginOptions", List.of(
                        loginOption("oauth",
                                "Login with Zerodha Kite in browser (requires your own API key from developers.kite.trade)",
                                List.of(),
                                "GET .../oauth-url → open popup → POST .../login { requestToken }")));
                m.put("requiresUserCredentials", true);
                m.put("credentialFields", List.of("apiKey", "apiSecret"));
                m.put("credentialNote", "Each user needs their own Kite Connect app. Go to developers.kite.trade → create app → set redirect URL to: " + (platformConfig.getCallbackUrl() != null ? platformConfig.getCallbackUrl() : "https://api.ascentracapital.com/api/v1/brokers/callback"));
                yield m;
            }
            case "FYERS" -> {
                Map<String, Object> m = brokerShell("FYERS", "Fyers", "oauth", "authCode");
                m.put("loginOptions", List.of(
                        loginOption("accessToken",
                                "Paste access token from Fyers API dashboard",
                                List.of("accessToken"),
                                "PUT /api/v1/brokers/accounts/{accountId}/token"),
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
                m.put("requiresUserCredentials", true);
                m.put("credentialFields", List.of("clientId", "apiSecret"));
                m.put("credentialNote", "First set your Angel One client code (clientId) and password (apiSecret) via Update Account, then login with TOTP code.");
                m.put("loginOptions", List.of(
                        loginOption("totp",
                                "TOTP from authenticator app (requires client code + password set first via Update Account)",
                                List.of("totpCode"),
                                "PUT /api/v1/brokers/accounts/{accountId} (set clientId + apiSecret) → POST .../login { totpCode }")));
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
        if ("GROWW".equals(a.getBrokerId())) {
            config.put("hasStoredApiKey", a.getApiKey() != null && !a.getApiKey().isBlank());
            // Always show both login methods for Groww reconnect
            config.put("availableLoginMethods", List.of("accessToken", "apiKeyWithTotp"));
            // If user has an API key stored, recommend TOTP login; otherwise recommend access token
            if (a.getApiKey() != null && !a.getApiKey().isBlank()) {
                config.put("recommendedLoginMethod", "apiKeyWithTotp");
            } else {
                config.put("recommendedLoginMethod", "accessToken");
            }
            // Ensure loginOptions always includes BOTH methods regardless of stored credentials
            config.put("loginOptions", List.of(
                    loginOption("accessToken",
                            "Paste access token from Groww settings (no API key needed)",
                            List.of("accessToken"),
                            "PUT /api/v1/brokers/accounts/" + a.getId() + "/token"),
                    loginOption("apiKeyWithTotp",
                            "API key + TOTP code from authenticator app",
                            List.of("apiKey", "totpCode"),
                            "POST /api/v1/brokers/accounts/" + a.getId() + "/login { totpCode }")));
        }
        List<String> methods = extractLoginOptionMethods(config.get("loginOptions"));
        config.put("loginOptionMethods", methods);
        // Dhan: include saved clientId so frontend can pre-fill on reconnect
        if ("DHAN".equals(a.getBrokerId()) && a.getClientId() != null && !a.getClientId().isBlank()) {
            config.put("savedClientId", a.getClientId());
        }
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
        if ("ANGELONE".equals(b)) {
            a.setClientId("");
        }
        // Dhan: keep clientId — it's needed for reconnect and order placement
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
                // Per-user Zerodha: use account's api_key, fallback to platform
                String zApiKey = (a.getApiKey() != null && !a.getApiKey().isBlank())
                        ? a.getApiKey() : (platformConfig.getZerodha() != null ? platformConfig.getZerodha().getApiKey() : null);
                if (zApiKey != null) {
                    config.put("oauthUrl", "https://kite.zerodha.com/connect/login?v=3&api_key=" + zApiKey);
                }
                if (a.getApiKey() == null || a.getApiKey().isBlank()) {
                    config.put("message", "Set your Zerodha API key+secret first (from developers.kite.trade), then open oauthUrl.");
                    config.put("needsCredentials", true);
                    config.put("requiredFields", List.of("apiKey", "apiSecret"));
                } else {
                    config.put("message", "Open oauthUrl in browser, then POST login with requestToken from callback.");
                }
            }
            case "FYERS" -> {
                var creds = platformConfig.getFyers();
                if (creds != null && creds.getApiKey() != null) {
                    String encodedRedirect;
                    try {
                        encodedRedirect = java.net.URLEncoder.encode(redirect, java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception ex) {
                        encodedRedirect = redirect;
                    }
                    config.put("oauthUrl", "https://api-t1.fyers.in/api/v3/generate-authcode?client_id="
                            + creds.getApiKey() + "&redirect_uri=" + encodedRedirect + "&response_type=code&state=ok");
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
        if (msg == null) return false;
        // 401 = definitely auth failure
        if (msg.contains("401") || msg.contains("Unauthorized")) return true;
        // 403 alone is NOT auth failure — Upstox/Groww use 403 for IP blocks and rate limits.
        // Only treat 403 as auth if combined with explicit token/session messages:
        if (msg.contains("403") && (msg.contains("token") || msg.contains("session") || msg.contains("login"))) return true;
        // "Invalid" only if clearly about auth (not "Invalid quantity" etc.)
        if (msg.contains("Invalid") && (msg.contains("token") || msg.contains("session") || msg.contains("key"))) return true;
        return false;
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

    public Mono<Map<String, Object>> disconnectAccount(UUID accountId, UUID userId) {
        return repo.findById(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Account not found")))
                .flatMap(account -> {
                    account.setAccessToken(null);
                    account.setSessionActive(false);
                    return repo.save(account);
                })
                .map(saved -> Map.of("message", "Account disconnected successfully"));
    }
}
