package com.copytrading.pnl;

import com.copytrading.broker.BrokerAccountService;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.logs.CopyLogRepository;
import com.copytrading.trade.TradeRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.*;

@RestController
public class PnlController {

    private final TradeRepository trades;
    private final CopyLogRepository copyLogs;
    private final BrokerAccountRepository brokerRepo;
    private final BrokerAccountService brokerService;

    public PnlController(TradeRepository trades, CopyLogRepository copyLogs,
                          BrokerAccountRepository brokerRepo, BrokerAccountService brokerService) {
        this.trades = trades;
        this.copyLogs = copyLogs;
        this.brokerRepo = brokerRepo;
        this.brokerService = brokerService;
    }

    /** 8.1 GET /pnl/realized */
    @GetMapping("/api/v1/pnl/realized")
    public Mono<Map<String, Object>> realized(@AuthenticationPrincipal String userId,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to,
                                               @RequestParam(required = false) UUID brokerAccountId) {
        return trades.findByUserIdOrderByPlacedAtDesc(UUID.fromString(userId)).collectList()
                .map(list -> {
                    double pnl = 0; // Real P&L would need buy/sell price matching
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("realizedPnl", pnl);
                    r.put("trades", list);
                    return r;
                });
    }

    /** 8.2 GET /pnl/unrealized */
    @GetMapping("/api/v1/pnl/unrealized")
    public Mono<Map<String, Object>> unrealized(@AuthenticationPrincipal String userId,
                                                 @RequestParam(required = false) UUID brokerAccountId) {
        if (brokerAccountId != null) {
            return brokerService.getPositions(brokerAccountId, UUID.fromString(userId))
                    .map(pos -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("unrealizedPnl", 0);
                        r.put("positions", pos.getOrDefault("positions", List.of()));
                        return r;
                    });
        }
        return Mono.just(Map.of("unrealizedPnl", 0, "positions", List.of()));
    }

    /** 8.3 GET /pnl/summary */
    @GetMapping("/api/v1/pnl/summary")
    public Mono<Map<String, Object>> summary(@AuthenticationPrincipal String userId,
                                              @RequestParam(required = false, defaultValue = "DAILY") String period) {
        return trades.findByUserIdOrderByPlacedAtDesc(UUID.fromString(userId)).collectList()
                .map(list -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("summary", List.of(Map.of("period", "today", "realizedPnl", 0,
                            "unrealizedPnl", 0, "totalTrades", list.size(), "winRate", 0)));
                    return r;
                });
    }

    /** 8.4 GET /pnl/child-vs-master */
    @GetMapping("/api/v1/pnl/child-vs-master")
    public Mono<Map<String, Object>> childVsMaster(@AuthenticationPrincipal String userId,
                                                    @RequestParam UUID masterId) {
        UUID childId = UUID.fromString(userId);
        return copyLogs.findByChildId(childId).collectList()
                .map(logs -> {
                    long success = logs.stream().filter(l -> "SUCCESS".equals(l.getChildStatus())).count();
                    long failed = logs.stream().filter(l -> "FAILED".equals(l.getChildStatus())).count();
                    long total = success + failed;
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("masterPnl", 0);
                    r.put("childPnl", 0);
                    r.put("replicationAccuracy", total > 0 ? (success * 100.0 / total) : 0);
                    r.put("failedReplications", failed);
                    return r;
                });
    }

    /** 8.5 GET /admin/pnl/all */
    @GetMapping("/api/v1/admin/pnl/all")
    public Mono<Map<String, Object>> adminPnl() {
        return trades.findAll().collectList()
                .map(all -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("totalRealizedPnl", 0);
                    r.put("topMasters", List.of());
                    r.put("totalTrades", all.size());
                    r.put("userPnl", List.of());
                    return r;
                });
    }
}
