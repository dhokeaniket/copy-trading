package com.copytrading.child;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/child")
public class ChildController {

    private final ChildService service;

    public ChildController(ChildService service) {
        this.service = service;
    }

    // 5.1 GET /child/masters
    @GetMapping("/masters")
    public Mono<Map<String, Object>> listMasters() {
        return service.listMasters();
    }

    // 5.2 POST /child/subscriptions
    @PostMapping(value = "/subscriptions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> subscribe(@AuthenticationPrincipal String userId,
                                                @RequestBody Map<String, String> body) {
        UUID masterId = UUID.fromString(body.get("masterId"));
        UUID brokerAccountId = body.get("brokerAccountId") != null ? UUID.fromString(body.get("brokerAccountId")) : null;
        return service.subscribe(UUID.fromString(userId), masterId, brokerAccountId);
    }

    // 5.3 DELETE /child/subscriptions/:masterId
    @DeleteMapping("/subscriptions/{masterId}")
    public Mono<Map<String, String>> unsubscribe(@AuthenticationPrincipal String userId,
                                                  @PathVariable UUID masterId) {
        return service.unsubscribe(UUID.fromString(userId), masterId);
    }

    // 5.4 GET /child/subscriptions
    @GetMapping("/subscriptions")
    public Mono<Map<String, Object>> listSubscriptions(@AuthenticationPrincipal String userId) {
        return service.listSubscriptions(UUID.fromString(userId));
    }

    // 5.5 GET /child/scaling
    @GetMapping("/scaling")
    public Mono<Map<String, Object>> getScaling(@AuthenticationPrincipal String userId,
                                                 @RequestParam(required = false) UUID masterId) {
        return service.getScaling(UUID.fromString(userId), masterId);
    }

    // 5.6 PUT /child/scaling
    @PutMapping(value = "/scaling", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> updateScaling(@AuthenticationPrincipal String userId,
                                                    @RequestBody Map<String, Object> body) {
        UUID masterId = UUID.fromString((String) body.get("masterId"));
        double sf = ((Number) body.get("scalingFactor")).doubleValue();
        return service.updateScaling(UUID.fromString(userId), masterId, sf);
    }

    // 5.7 POST /child/copying/pause
    @PostMapping(value = "/copying/pause", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> pauseCopying(@AuthenticationPrincipal String userId,
                                                   @RequestBody Map<String, String> body) {
        return service.pauseCopying(UUID.fromString(userId), UUID.fromString(body.get("masterId")));
    }

    // 5.8 POST /child/copying/resume
    @PostMapping(value = "/copying/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> resumeCopying(@AuthenticationPrincipal String userId,
                                                    @RequestBody Map<String, String> body) {
        return service.resumeCopying(UUID.fromString(userId), UUID.fromString(body.get("masterId")));
    }

    // 5.9 GET /child/copied-trades
    @GetMapping("/copied-trades")
    public Mono<Map<String, Object>> getCopiedTrades(@AuthenticationPrincipal String userId) {
        return service.getCopiedTrades(UUID.fromString(userId));
    }

    // 5.10 GET /child/analytics
    @GetMapping("/analytics")
    public Mono<Map<String, Object>> getAnalytics(@AuthenticationPrincipal String userId) {
        return service.getAnalytics(UUID.fromString(userId));
    }
}
