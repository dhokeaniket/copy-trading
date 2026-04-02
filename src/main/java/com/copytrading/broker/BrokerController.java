package com.copytrading.broker;

import com.copytrading.broker.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
public class BrokerController {

    private final BrokerAccountService service;

    public BrokerController(BrokerAccountService service) {
        this.service = service;
    }

    // 3.1 GET /brokers
    @GetMapping("/api/v1/brokers")
    public Mono<Map<String, Object>> listBrokers() {
        return service.listBrokers();
    }

    // 3.2 POST /brokers/accounts
    @PostMapping(value = "/api/v1/brokers/accounts", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> linkAccount(@AuthenticationPrincipal String userId,
                                                  @RequestBody LinkAccountRequest req) {
        return service.linkAccount(UUID.fromString(userId), req);
    }

    // 3.3 GET /brokers/accounts
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

    // 3.6 DELETE /brokers/accounts/:accountId
    @DeleteMapping("/api/v1/brokers/accounts/{accountId}")
    public Mono<Map<String, String>> deleteAccount(@PathVariable UUID accountId,
                                                    @AuthenticationPrincipal String userId) {
        return service.deleteAccount(accountId, UUID.fromString(userId));
    }

    // 3.7 POST /brokers/accounts/:accountId/login
    @PostMapping(value = "/api/v1/brokers/accounts/{accountId}/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> loginToBroker(@PathVariable UUID accountId,
                                                    @AuthenticationPrincipal String userId,
                                                    @RequestBody(required = false) BrokerLoginRequest req) {
        return service.loginToBroker(accountId, UUID.fromString(userId), req);
    }

    // 3.8 GET /brokers/accounts/:accountId/status
    @GetMapping("/api/v1/brokers/accounts/{accountId}/status")
    public Mono<Map<String, Object>> getSessionStatus(@PathVariable UUID accountId,
                                                       @AuthenticationPrincipal String userId) {
        return service.getSessionStatus(accountId, UUID.fromString(userId));
    }

    // 3.9 GET /brokers/accounts/:accountId/margin
    @GetMapping("/api/v1/brokers/accounts/{accountId}/margin")
    public Mono<Map<String, Object>> getMargin(@PathVariable UUID accountId,
                                                @AuthenticationPrincipal String userId) {
        return service.getMargin(accountId, UUID.fromString(userId));
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
}
