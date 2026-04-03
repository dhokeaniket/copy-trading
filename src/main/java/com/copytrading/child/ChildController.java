package com.copytrading.child;

import com.copytrading.child.dto.ChildScalingRequest;
import com.copytrading.child.dto.MasterIdRequest;
import com.copytrading.child.dto.SubscribeRequest;
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

    public ChildController(ChildService service) { this.service = service; }

    @GetMapping("/masters")
    public Mono<Map<String, Object>> listMasters() { return service.listMasters(); }

    @PostMapping(value = "/subscriptions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> subscribe(@AuthenticationPrincipal String userId,
                                                @RequestBody SubscribeRequest req) {
        return service.subscribe(UUID.fromString(userId), req.getMasterId(), req.getBrokerAccountId());
    }

    @DeleteMapping("/subscriptions/{masterId}")
    public Mono<Map<String, String>> unsubscribe(@AuthenticationPrincipal String userId, @PathVariable UUID masterId) {
        return service.unsubscribe(UUID.fromString(userId), masterId);
    }

    @GetMapping("/subscriptions")
    public Mono<Map<String, Object>> listSubscriptions(@AuthenticationPrincipal String userId) {
        return service.listSubscriptions(UUID.fromString(userId));
    }

    @GetMapping("/scaling")
    public Mono<Map<String, Object>> getScaling(@AuthenticationPrincipal String userId,
                                                 @RequestParam(required = false) UUID masterId) {
        return service.getScaling(UUID.fromString(userId), masterId);
    }

    @PutMapping(value = "/scaling", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> updateScaling(@AuthenticationPrincipal String userId,
                                                    @RequestBody ChildScalingRequest req) {
        return service.updateScaling(UUID.fromString(userId), req.getMasterId(), req.getScalingFactor());
    }

    @PostMapping(value = "/copying/pause", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> pauseCopying(@AuthenticationPrincipal String userId,
                                                   @RequestBody MasterIdRequest req) {
        return service.pauseCopying(UUID.fromString(userId), req.getMasterId());
    }

    @PostMapping(value = "/copying/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> resumeCopying(@AuthenticationPrincipal String userId,
                                                    @RequestBody MasterIdRequest req) {
        return service.resumeCopying(UUID.fromString(userId), req.getMasterId());
    }

    @GetMapping("/copied-trades")
    public Mono<Map<String, Object>> getCopiedTrades(@AuthenticationPrincipal String userId) {
        return service.getCopiedTrades(UUID.fromString(userId));
    }

    @GetMapping("/analytics")
    public Mono<Map<String, Object>> getAnalytics(@AuthenticationPrincipal String userId) {
        return service.getAnalytics(UUID.fromString(userId));
    }
}
