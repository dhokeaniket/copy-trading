package com.copytrading.master;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/master")
public class MasterController {

    private final MasterService service;

    public MasterController(MasterService service) {
        this.service = service;
    }

    // 4.1 GET /master/children
    @GetMapping("/children")
    public Mono<Map<String, Object>> listChildren(@AuthenticationPrincipal String userId) {
        return service.listChildren(UUID.fromString(userId));
    }

    // 4.2 POST /master/children/:childId/link
    @PostMapping(value = "/children/{childId}/link", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> linkChild(@AuthenticationPrincipal String userId,
                                                @PathVariable UUID childId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        Double sf = body != null && body.get("scalingFactor") != null
                ? ((Number) body.get("scalingFactor")).doubleValue() : null;
        return service.linkChild(UUID.fromString(userId), childId, sf);
    }

    // 4.3 DELETE /master/children/:childId/unlink
    @DeleteMapping("/children/{childId}/unlink")
    public Mono<Map<String, String>> unlinkChild(@AuthenticationPrincipal String userId,
                                                  @PathVariable UUID childId) {
        return service.unlinkChild(UUID.fromString(userId), childId);
    }

    // 4.4 GET /master/children/:childId/scaling
    @GetMapping("/children/{childId}/scaling")
    public Mono<Map<String, Object>> getScaling(@AuthenticationPrincipal String userId,
                                                 @PathVariable UUID childId) {
        return service.getScaling(UUID.fromString(userId), childId);
    }

    // 4.5 PUT /master/children/:childId/scaling
    @PutMapping(value = "/children/{childId}/scaling", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> updateScaling(@AuthenticationPrincipal String userId,
                                                    @PathVariable UUID childId,
                                                    @RequestBody Map<String, Object> body) {
        double sf = ((Number) body.get("scalingFactor")).doubleValue();
        return service.updateScaling(UUID.fromString(userId), childId, sf);
    }

    // 4.6 GET /master/analytics
    @GetMapping("/analytics")
    public Mono<Map<String, Object>> getAnalytics(@AuthenticationPrincipal String userId) {
        return service.getAnalytics(UUID.fromString(userId));
    }

    // 4.7 GET /master/trade-history
    @GetMapping("/trade-history")
    public Mono<Map<String, Object>> getTradeHistory(@AuthenticationPrincipal String userId) {
        return service.getTradeHistory(UUID.fromString(userId));
    }
}
