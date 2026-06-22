package com.copytrading.broker;

import com.copytrading.alert.BalanceAlertService;
import com.copytrading.broker.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "2. Broker Accounts", description = "Link broker accounts, login, margin, positions, orders, holdings, dashboard")
public class BrokerController {

    // ── Singular /broker/ aliases (frontend uses singular, backend uses plural) ──
    @GetMapping("/api/v1/broker/accounts")
    public Mono<Map<String, Object>> listAccountsSingular(@AuthenticationPrincipal String userId) {
        return service.listAccounts(UUID.fromString(userId));
    }
    @GetMapping("/api/v1/broker/accounts/{accountId}")
    public Mono<BrokerAccountDto> getAccountSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId) {
        return service.getAccount(accountId, UUID.fromString(userId));
    }
    @GetMapping("/api/v1/broker/accounts/{accountId}/dashboard")
    public Mono<Map<String, Object>> getDashboardSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId) {
        return service.getDashboard(accountId, UUID.fromString(userId));
    }
    @GetMapping("/api/v1/broker/accounts/{accountId}/positions")
    public Mono<Map<String, Object>> getPositionsSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId) {
        return service.getPositions(accountId, UUID.fromString(userId));
    }
    @GetMapping("/api/v1/broker/accounts/{accountId}/margin")
    public Mono<Map<String, Object>> getMarginSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId) {
        return service.getMargin(accountId, UUID.fromString(userId));
    }
    @GetMapping("/api/v1/broker/accounts/{accountId}/orders")
    public Mono<Map<String, Object>> getOrdersSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId) {
        return service.getOrders(accountId, UUID.fromString(userId));
    }
    @GetMapping("/api/v1/broker/accounts/{accountId}/trades")
    public Mono<Map<String, Object>> getTradesSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId) {
        return service.getTrades(accountId, UUID.fromString(userId));
    }
    @GetMapping("/api/v1/broker/accounts/{accountId}/holdings")
    public Mono<Map<String, Object>> getHoldingsSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId) {
        return service.getHoldings(accountId, UUID.fromString(userId));
    }
    @GetMapping("/api/v1/broker/accounts/{accountId}/status")
    public Mono<Map<String, Object>> getStatusSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId) {
        return service.getSessionStatus(accountId, UUID.fromString(userId));
    }
    @GetMapping("/api/v1/broker/accounts/{accountId}/profile")
    public Mono<Map<String, Object>> getProfileSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId) {
        return profileService.getProfile(accountId, UUID.fromString(userId), false);
    }
    @GetMapping("/api/v1/broker/accounts/{accountId}/signal")
    public Mono<Map<String, Object>> getSignalSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId) {
        return service.getConnectionSignal(accountId, UUID.fromString(userId));
    }
    @PostMapping(value = "/api/v1/broker/accounts/{accountId}/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> loginSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId, @RequestBody(required = false) BrokerLoginRequest req) {
        return service.loginToBroker(accountId, UUID.fromString(userId), req);
    }
    @PutMapping(value = "/api/v1/broker/accounts/{accountId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> updateAccountSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId, @RequestBody UpdateAccountRequest req) {
        return service.updateAccount(accountId, UUID.fromString(userId), req);
    }
    @DeleteMapping("/api/v1/broker/accounts/{accountId}")
    public Mono<Map<String, String>> deleteAccountSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId) {
        return service.deleteAccount(accountId, UUID.fromString(userId));
    }
    @PostMapping(value = "/api/v1/broker/accounts", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> linkAccountSingular(@AuthenticationPrincipal String userId, @RequestBody LinkAccountRequest req) {
        return service.linkAccount(UUID.fromString(userId), req);
    }
    @PostMapping("/api/v1/broker/accounts/{accountId}/disconnect")
    public Mono<Map<String, Object>> disconnectSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId) {
        return service.disconnectBroker(accountId, UUID.fromString(userId));
    }
    @PutMapping(value = "/api/v1/broker/accounts/{accountId}/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> setTokenSingular(@PathVariable UUID accountId, @AuthenticationPrincipal String userId, @RequestBody Map<String, String> body) {
        return service.setAccessToken(accountId, UUID.fromString(userId), body.get("accessToken"));
    }
    // ── End singular aliases ──

    private final BrokerAccountService service;
    private final BalanceAlertService balanceAlertService;
    private final BrokerProfileService profileService;

    public BrokerController(BrokerAccountService service, BalanceAlertService balanceAlertService,
                            BrokerProfileService profileService) {
        this.service = service;
        this.balanceAlertService = balanceAlertService;
        this.profileService = profileService;
    }

    @Operation(summary = "List supported brokers", description = "Returns all supported brokers with login methods")
    @GetMapping("/api/v1/brokers")
    public Mono<Map<String, Object>> listBrokers() {
        return service.listBrokers();
    }

    @Operation(summary = "Link broker account", description = "Link a new broker account. For Groww: provide apiKey+apiSecret. For OAuth brokers: just brokerId and nickname.")
    @PostMapping(value = "/api/v1/brokers/accounts", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> linkAccount(@AuthenticationPrincipal String userId,
                                                  @RequestBody LinkAccountRequest req) {
        return service.linkAccount(UUID.fromString(userId), req);
    }

    @Operation(summary = "List my broker accounts")
    @GetMapping("/api/v1/brokers/accounts")
    public Mono<Map<String, Object>> listAccounts(@AuthenticationPrincipal String userId) {
        return service.listAccounts(UUID.fromString(userId));
    }

    // 3.4 GET /brokers/accounts/:accountId
    @GetMapping("/api/v1/brokers/accounts/{accountId}")
    public Mono<BrokerAccountDto> getAccount(@PathVariable UUID accountId,
                                              @AuthenticationPrincipal String userId) {
        return service.getAccount(accountId, UUID.fromString(userId));
    }

    // 3.5 PUT /brokers/accounts/:accountId
    @PutMapping(value = "/api/v1/brokers/accounts/{accountId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> updateAccount(@PathVariable UUID accountId,
                                                    @AuthenticationPrincipal String userId,
                                                    @RequestBody UpdateAccountRequest req) {
        return service.updateAccount(accountId, UUID.fromString(userId), req);
    }

    @Operation(summary = "Toggle copy enable for broker account")
    @PatchMapping(value = "/api/v1/brokers/accounts/{accountId}/copy-enable", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<BrokerAccountDto> toggleCopyEnable(@PathVariable UUID accountId,
                                                   @AuthenticationPrincipal String userId,
                                                   @RequestBody Map<String, Boolean> body) {
        Boolean isCopyEnable = body.get("isCopyEnable");
        if (isCopyEnable == null) {
            isCopyEnable = true;
        }
        return service.toggleCopyEnable(accountId, UUID.fromString(userId), isCopyEnable);
    }

    // 3.6 DELETE /brokers/accounts/:accountId
    @DeleteMapping("/api/v1/brokers/accounts/{accountId}")
    public Mono<Map<String, String>> deleteAccount(@PathVariable UUID accountId,
                                                    @AuthenticationPrincipal String userId) {
        return service.deleteAccount(accountId, UUID.fromString(userId));
    }

    @Operation(summary = "Disconnect broker session", description = "Clears session; returns full loginOptions (Groww: access token + API key/TOTP) for reconnect")
    @PostMapping("/api/v1/brokers/accounts/{accountId}/disconnect")
    public Mono<Map<String, Object>> disconnectBroker(@PathVariable UUID accountId,
                                                       @AuthenticationPrincipal String userId) {
        return service.disconnectBroker(accountId, UUID.fromString(userId));
    }

    @Operation(summary = "Login options for reconnect", description = "Same loginOptions as GET /brokers — use after disconnect or when session expired")
    @GetMapping("/api/v1/brokers/accounts/{accountId}/login-options")
    public Mono<Map<String, Object>> getLoginOptions(@PathVariable UUID accountId,
                                                      @AuthenticationPrincipal String userId) {
        return service.getLoginOptions(accountId, UUID.fromString(userId));
    }

    @Operation(summary = "Login to broker", description = "Create broker session. Groww={}, Zerodha={requestToken}, Dhan={authCode:tokenId}. Fyers/Upstox: PUT apiKey+apiSecret (each user's developer app) then {authCode, redirectUri?} after GET oauth-url. AngelOne: {totpCode} after PUT apiKey, clientId, apiSecret (MPIN). redirectUri must match GET .../oauth-url for OAuth brokers.")
    @PostMapping(value = "/api/v1/brokers/accounts/{accountId}/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> loginToBroker(@PathVariable UUID accountId,
                                                    @AuthenticationPrincipal String userId,
                                                    @RequestBody(required = false) BrokerLoginRequest req) {
        return service.loginToBroker(accountId, UUID.fromString(userId), req);
    }

    @Operation(summary = "OAuth URL and login options",
            description = "Returns loginOptions plus oauthUrl for OAuth brokers. "
                    + "Fyers/Upstox without PUT apiKey+apiSecret: oauthUrl is null, errorCode=CREDENTIALS_REQUIRED, action=PUT_BROKER_CREDENTIALS, "
                    + "effectiveRedirectUri and brokerRedirectRegistrationHint show the redirect URL to register in the broker developer console.")
    @GetMapping("/api/v1/brokers/accounts/{accountId}/oauth-url")
    public Mono<Map<String, Object>> getOAuthUrl(@PathVariable UUID accountId,
                                                  @AuthenticationPrincipal String userId,
                                                  @RequestParam(required = false) String redirectUri) {
        return service.getOAuthUrl(accountId, UUID.fromString(userId), redirectUri);
    }

    // 3.8 GET /brokers/accounts/:accountId/status
    @GetMapping("/api/v1/brokers/accounts/{accountId}/status")
    public Mono<Map<String, Object>> getSessionStatus(@PathVariable UUID accountId,
                                                       @AuthenticationPrincipal String userId) {
        return service.getSessionStatus(accountId, UUID.fromString(userId));
    }

    // 3.8b GET /brokers/accounts/:accountId/test — Test connection
    @GetMapping("/api/v1/brokers/accounts/{accountId}/test")
    public Mono<Map<String, Object>> testConnection(@PathVariable UUID accountId,
                                                     @AuthenticationPrincipal String userId) {
        return service.testConnection(accountId, UUID.fromString(userId));
    }

    // 3.9 GET /brokers/accounts/:accountId/margin
    @GetMapping("/api/v1/brokers/accounts/{accountId}/margin")
    public Mono<Map<String, Object>> getMargin(@PathVariable UUID accountId,
                                                @AuthenticationPrincipal String userId) {
        return service.getMargin(accountId, UUID.fromString(userId));
    }

    @Operation(summary = "Normalized broker profile", description = "Spec §3.1 — unified account profile")
    @GetMapping("/api/v1/brokers/accounts/{accountId}/profile")
    public Mono<Map<String, Object>> getAccountProfile(@PathVariable UUID accountId,
                                                          @AuthenticationPrincipal String userId) {
        return profileService.getProfile(accountId, UUID.fromString(userId), false);
    }

    @Operation(summary = "Refresh broker profile", description = "Spec §3.8 — force refresh from broker API")
    @PostMapping("/api/v1/brokers/accounts/{accountId}/refresh-profile")
    public Mono<Map<String, Object>> refreshAccountProfile(@PathVariable UUID accountId,
                                                              @AuthenticationPrincipal String userId) {
        return profileService.getProfile(accountId, UUID.fromString(userId), true);
    }

    // 3.10 GET /brokers/accounts/:accountId/positions
    @GetMapping("/api/v1/brokers/accounts/{accountId}/positions")
    public Mono<Map<String, Object>> getPositions(@PathVariable UUID accountId,
                                                   @AuthenticationPrincipal String userId) {
        return service.getPositions(accountId, UUID.fromString(userId));
    }

    // 3.11 GET /admin/brokers/accounts (Admin only)
    @GetMapping("/api/v1/admin/brokers/accounts")
    public Mono<Map<String, Object>> adminListAccounts(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String brokerId) {
        return service.adminListAccounts(userId, brokerId);
    }

    // 3.12 GET /admin/brokers/status (Admin only)
    @GetMapping("/api/v1/admin/brokers/status")
    public Mono<Map<String, Object>> adminBrokerStatus() {
        return service.adminBrokerStatus();
    }

    @Operation(summary = "Broker dashboard", description = "All-in-one: profile + margin + positions + holdings + orders + signal")
    @GetMapping("/api/v1/brokers/accounts/{accountId}/dashboard")
    public Mono<Map<String, Object>> getDashboard(@PathVariable UUID accountId,
                                                   @AuthenticationPrincipal String userId) {
        return service.getDashboard(accountId, UUID.fromString(userId));
    }

    // Orders
    @GetMapping("/api/v1/brokers/accounts/{accountId}/orders")
    public Mono<Map<String, Object>> getOrders(@PathVariable UUID accountId,
                                                @AuthenticationPrincipal String userId) {
        return service.getOrders(accountId, UUID.fromString(userId));
    }

    // Trades
    @GetMapping("/api/v1/brokers/accounts/{accountId}/trades")
    public Mono<Map<String, Object>> getTrades(@PathVariable UUID accountId,
                                                @AuthenticationPrincipal String userId) {
        return service.getTrades(accountId, UUID.fromString(userId));
    }

    // Holdings
    @GetMapping("/api/v1/brokers/accounts/{accountId}/holdings")
    public Mono<Map<String, Object>> getHoldings(@PathVariable UUID accountId,
                                                  @AuthenticationPrincipal String userId) {
        return service.getHoldings(accountId, UUID.fromString(userId));
    }

    // Close position
    @PostMapping(value = "/api/v1/brokers/accounts/{accountId}/orders/close-position", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> closePosition(@PathVariable UUID accountId,
                                                    @AuthenticationPrincipal String userId,
                                                    @RequestBody com.copytrading.broker.dto.ClosePositionRequest req) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("symbol", req.getSymbol());
        body.put("qty", req.getQty());
        body.put("type", req.getType());
        body.put("product", req.getProduct());
        return service.closePosition(accountId, UUID.fromString(userId), body);
    }

    // Cancel order
    @DeleteMapping("/api/v1/brokers/accounts/{accountId}/orders/{orderId}")
    public Mono<Map<String, Object>> cancelOrder(@PathVariable UUID accountId,
                                                  @AuthenticationPrincipal String userId,
                                                  @PathVariable String orderId) {
        return service.cancelOrder(accountId, UUID.fromString(userId), orderId);
    }

    // Balance alert check
    @GetMapping("/api/v1/brokers/accounts/{accountId}/balance-alert")
    public Mono<Map<String, Object>> checkBalanceAlert(@PathVariable UUID accountId,
                                                        @AuthenticationPrincipal String userId) {
        return balanceAlertService.checkBalance(accountId, UUID.fromString(userId));
    }

    @Operation(summary = "Set access token directly", description = "Directly save a broker access token (e.g. from Groww dashboard). Activates the session.")
    @PutMapping(value = "/api/v1/brokers/accounts/{accountId}/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> setToken(@PathVariable UUID accountId,
                                               @AuthenticationPrincipal String userId,
                                               @RequestBody Map<String, String> body) {
        return service.setAccessToken(accountId, UUID.fromString(userId), body.get("accessToken"));
    }

    // Connection signal (like mobile network bars: 1-4)
    @GetMapping("/api/v1/brokers/accounts/{accountId}/signal")
    public Mono<Map<String, Object>> getConnectionSignal(@PathVariable UUID accountId,
                                                          @AuthenticationPrincipal String userId) {
        return service.getConnectionSignal(accountId, UUID.fromString(userId));
    }

    // Broker OAuth callback — captures request_token/auth_code from broker redirect
    @GetMapping("/api/v1/brokers/callback")
    public Mono<Map<String, Object>> oauthCallback(
            @RequestParam(required = false) String request_token,
            @RequestParam(required = false) String auth_code,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String tokenId,
            @RequestParam(required = false) String status) {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("message", "Broker OAuth callback received. Use the token below to call the login API.");
        if (request_token != null) {
            r.put("broker", "ZERODHA");
            r.put("requestToken", request_token);
            r.put("loginBody", Map.of("requestToken", request_token));
        } else if (auth_code != null) {
            r.put("broker", "FYERS");
            r.put("authCode", auth_code);
            r.put("loginBody", Map.of("authCode", auth_code));
        } else if (tokenId != null) {
            r.put("broker", "DHAN");
            r.put("tokenId", tokenId);
            r.put("loginBody", Map.of("authCode", tokenId));
        } else if (code != null) {
            r.put("broker", "UPSTOX");
            r.put("authCode", code);
            r.put("loginBody", Map.of("authCode", code));
        }
        if (status != null) r.put("status", status);
        return Mono.just(r);
    }
}
