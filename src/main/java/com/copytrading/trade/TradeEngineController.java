package com.copytrading.trade;

import com.copytrading.auth.JwtService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trades")
public class TradeEngineController {

    private final TradeEngineService service;
    private final JwtService jwtService;

    public TradeEngineController(TradeEngineService service, JwtService jwtService) {
        this.service = service;
        this.jwtService = jwtService;
    }

    @PostMapping(value = "/execute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> execute(@AuthenticationPrincipal String userId,
                                              @RequestHeader("Authorization") String authHeader,
                                              @RequestBody Map<String, Object> body) {
        String role = extractRole(authHeader);
        return service.executeTrade(UUID.fromString(userId), role, body);
    }

    @GetMapping
    public Mono<Map<String, Object>> listTrades(@AuthenticationPrincipal String userId,
                                                 @RequestParam(required = false) String status) {
        return service.listTrades(UUID.fromString(userId), status);
    }

    @GetMapping("/{tradeId}")
    public Mono<Trade> getTrade(@AuthenticationPrincipal String userId, @PathVariable UUID tradeId) {
        return service.getTrade(tradeId, UUID.fromString(userId));
    }

    @DeleteMapping("/{tradeId}/cancel")
    public Mono<Map<String, Object>> cancelTrade(@AuthenticationPrincipal String userId, @PathVariable UUID tradeId) {
        return service.cancelTrade(tradeId, UUID.fromString(userId));
    }

    @GetMapping("/{tradeId}/replications")
    public Mono<Map<String, Object>> getReplications(@AuthenticationPrincipal String userId, @PathVariable UUID tradeId) {
        return service.getTradeReplications(tradeId, UUID.fromString(userId));
    }

    @GetMapping("/open-positions")
    public Mono<Map<String, Object>> openPositions(@AuthenticationPrincipal String userId,
                                                    @RequestParam(required = false) UUID brokerAccountId) {
        return service.getOpenPositions(UUID.fromString(userId), brokerAccountId);
    }

    @PostMapping(value = "/basket", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> basket(@AuthenticationPrincipal String userId,
                                             @RequestHeader("Authorization") String authHeader,
                                             @RequestBody Map<String, Object> body) {
        return service.placeBasketOrder(UUID.fromString(userId), extractRole(authHeader), body);
    }

    private String extractRole(String authHeader) {
        try {
            String token = authHeader.replace("Bearer ", "");
            return jwtService.extractRole(token);
        } catch (Exception e) { return "CHILD"; }
    }
}
