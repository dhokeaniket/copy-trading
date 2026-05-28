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
        UUID uid = UUID.fromString(userId);
        if (brokerAccountId != null) {
            return brokerService.getPositions(brokerAccountId, uid)
                    .map(pos -> buildUnrealizedResponse(pos));
        }
        return brokerRepo.findByUserId(uid)
                .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                .next()
                .flatMap(a -> brokerService.getPositions(a.getId(), uid).map(this::buildUnrealizedResponse))
                .switchIfEmpty(Mono.just(Map.of("unrealizedPnl", 0, "positions", List.of())));
    }

    /** 8.3 GET /pnl/summary */
    @GetMapping("/api/v1/pnl/summary")
    public Mono<Map<String, Object>> summary(@AuthenticationPrincipal String userId,
                                              @RequestParam(required = false, defaultValue = "DAILY") String period) {
        UUID uid = UUID.fromString(userId);
        Mono<Double> unrealizedMono = brokerRepo.findByUserId(uid)
                .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                .next()
                .flatMap(a -> brokerService.getPositions(a.getId(), uid).map(this::sumUnrealizedPnl))
                .defaultIfEmpty(0.0);
        Mono<List<com.copytrading.logs.CopyLog>> logsMono = copyLogs.findByChildId(uid).collectList()
                .flatMap(list -> list.isEmpty() ? copyLogs.findByMasterId(uid).collectList() : Mono.just(list));
        return Mono.zip(
                logsMono,
                trades.findByUserIdOrderByPlacedAtDesc(uid).collectList(),
                unrealizedMono
        ).map(t -> {
            var copyList = t.getT1();
            var tradeList = t.getT2();
            double unrealized = t.getT3();
            long copied = copyList.stream().filter(l -> "SUCCESS".equals(l.getChildStatus())).count();
            long failed = copyList.stream().filter(l -> "FAILED".equals(l.getChildStatus())).count();
            long total = copied + failed;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("summary", List.of(Map.of(
                    "period", period.toLowerCase(),
                    "realizedPnl", 0,
                    "unrealizedPnl", unrealized,
                    "totalTrades", tradeList.size(),
                    "copiedTrades", copied,
                    "failedCopies", failed,
                    "winRate", total > 0 ? Math.round(copied * 100.0 / total) : 0)));
            return r;
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildUnrealizedResponse(Map<String, Object> pos) {
        double pnl = sumUnrealizedPnl(pos);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("unrealizedPnl", pnl);
        r.put("positions", pos.getOrDefault("positions", List.of()));
        return r;
    }

    @SuppressWarnings("unchecked")
    private double sumUnrealizedPnl(Map<String, Object> pos) {
        Object raw = pos.get("positions");
        if (!(raw instanceof List<?> list)) return 0;
        double sum = 0;
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                sum += toDouble(m.get("pnl"));
                if (sum == 0) sum += toDouble(m.get("unrealizedPnl"));
            }
        }
        return Math.round(sum * 100.0) / 100.0;
    }

    private static double toDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0; }
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
