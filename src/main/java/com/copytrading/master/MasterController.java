package com.copytrading.master;

import com.copytrading.master.dto.LinkChildRequest;
import com.copytrading.master.dto.UpdateScalingRequest;
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

    public MasterController(MasterService service) { this.service = service; }

    @GetMapping("/children")
    public Mono<Map<String, Object>> listChildren(@AuthenticationPrincipal String userId) {
        return service.listChildren(UUID.fromString(userId));
    }

    @PostMapping(value = "/children/{childId}/link", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> linkChild(@AuthenticationPrincipal String userId,
                                                @PathVariable UUID childId,
                                                @RequestBody(required = false) LinkChildRequest req) {
        return service.linkChild(UUID.fromString(userId), childId, req != null ? req.getScalingFactor() : null);
    }

    @DeleteMapping("/children/{childId}/unlink")
    public Mono<Map<String, String>> unlinkChild(@AuthenticationPrincipal String userId, @PathVariable UUID childId) {
        return service.unlinkChild(UUID.fromString(userId), childId);
    }

    @GetMapping("/children/{childId}/scaling")
    public Mono<Map<String, Object>> getScaling(@AuthenticationPrincipal String userId, @PathVariable UUID childId) {
        return service.getScaling(UUID.fromString(userId), childId);
    }

    @PutMapping(value = "/children/{childId}/scaling", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> updateScaling(@AuthenticationPrincipal String userId,
                                                    @PathVariable UUID childId,
                                                    @RequestBody UpdateScalingRequest req) {
        return service.updateScaling(UUID.fromString(userId), childId, req.getScalingFactor());
    }

    @GetMapping("/analytics")
    public Mono<Map<String, Object>> getAnalytics(@AuthenticationPrincipal String userId) {
        return service.getAnalytics(UUID.fromString(userId));
    }

    @GetMapping("/trade-history")
    public Mono<Map<String, Object>> getTradeHistory(@AuthenticationPrincipal String userId) {
        return service.getTradeHistory(UUID.fromString(userId));
    }
}
