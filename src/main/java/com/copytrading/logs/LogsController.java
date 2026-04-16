package com.copytrading.logs;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.*;

@RestController
public class LogsController {

    private final TradeLogRepository tradeLogs;
    private final BrokerErrorLogRepository brokerErrors;

    public LogsController(TradeLogRepository tradeLogs, BrokerErrorLogRepository brokerErrors) {
        this.tradeLogs = tradeLogs;
        this.brokerErrors = brokerErrors;
    }

    /** 9.1 GET /logs/trades */
    @GetMapping("/api/v1/logs/trades")
    public Mono<Map<String, Object>> userTradeLogs(@AuthenticationPrincipal String userId) {
        UUID uid = UUID.fromString(userId);
        return tradeLogs.findByMasterId(uid).collectList()
                .flatMap(masterLogs -> tradeLogs.findByChildId(uid).collectList()
                        .map(childLogs -> {
                            List<TradeLog> all = new ArrayList<>(masterLogs);
                            all.addAll(childLogs);
                            all.sort(Comparator.comparing(TradeLog::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
                            return Map.<String, Object>of("logs", all);
                        }));
    }

    /** 9.2 GET /logs/broker-errors */
    @GetMapping("/api/v1/logs/broker-errors")
    public Mono<Map<String, Object>> userBrokerErrors(@AuthenticationPrincipal String userId,
                                                       @RequestParam(required = false) UUID brokerAccountId) {
        var flux = brokerAccountId != null
                ? brokerErrors.findByBrokerAccountIdOrderByCreatedAtDesc(brokerAccountId)
                : brokerErrors.findByUserIdOrderByCreatedAtDesc(UUID.fromString(userId));
        return flux.collectList().map(list -> Map.<String, Object>of("errors", list));
    }

    /** 9.3 GET /admin/logs/trades */
    @GetMapping("/api/v1/admin/logs/trades")
    public Mono<Map<String, Object>> adminTradeLogs(@RequestParam(required = false) UUID userId,
                                                     @RequestParam(required = false) String status) {
        var flux = userId != null ? tradeLogs.findByMasterId(userId) : tradeLogs.findAll();
        return flux.collectList().map(list -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("logs", list);
            r.put("total", list.size());
            return r;
        });
    }

    /** 9.4 GET /admin/logs/system */
    @GetMapping("/api/v1/admin/logs/system")
    public Mono<Map<String, Object>> systemLogs() {
        // System logs would come from a logging framework; return runtime info for now
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("logs", List.of(
                Map.of("level", "INFO", "service", "copy-trading", "message", "System running",
                        "timestamp", java.time.Instant.now().toString(),
                        "freeMemoryMB", rt.freeMemory() / 1024 / 1024,
                        "totalMemoryMB", rt.totalMemory() / 1024 / 1024,
                        "maxMemoryMB", rt.maxMemory() / 1024 / 1024)
        ));
        return Mono.just(r);
    }

    /** 9.5 GET /admin/logs/broker-errors */
    @GetMapping("/api/v1/admin/logs/broker-errors")
    public Mono<Map<String, Object>> adminBrokerErrors(@RequestParam(required = false) String brokerId,
                                                        @RequestParam(required = false) UUID userId) {
        var flux = userId != null
                ? brokerErrors.findByUserIdOrderByCreatedAtDesc(userId)
                : brokerErrors.findAllByOrderByCreatedAtDesc();
        return flux.collectList().map(list -> Map.<String, Object>of("errors", list));
    }
}
