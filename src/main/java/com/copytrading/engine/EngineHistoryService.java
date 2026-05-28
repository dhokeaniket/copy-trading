package com.copytrading.engine;

import com.copytrading.auth.UserAccountRepository;
import com.copytrading.logs.CopyLog;
import com.copytrading.logs.CopyLogRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/** Spec §1 — trade history & latency analytics from {@code copy_logs}. */
@Service
public class EngineHistoryService {

    private final CopyLogRepository copyLogs;
    private final UserAccountRepository users;

    public EngineHistoryService(CopyLogRepository copyLogs, UserAccountRepository users) {
        this.copyLogs = copyLogs;
        this.users = users;
    }

    public Mono<Map<String, Object>> getTradeHistory(UUID masterId, int page, int size,
                                                     String from, String to, String symbol, String side) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return copyLogs.findByMasterId(masterId)
                .filter(l -> l.getCopyGroupId() != null && !l.getCopyGroupId().isBlank())
                .filter(l -> filterLog(l, from, to, symbol, side))
                .collectList()
                .map(logs -> {
                    Map<String, List<CopyLog>> groups = logs.stream()
                            .collect(Collectors.groupingBy(CopyLog::getCopyGroupId));
                    List<Map<String, Object>> events = groups.entrySet().stream()
                            .map(e -> toEventSummary(e.getKey(), e.getValue()))
                            .sorted((a, b) -> String.valueOf(b.get("masterTriggeredAt"))
                                    .compareTo(String.valueOf(a.get("masterTriggeredAt"))))
                            .toList();
                    int total = events.size();
                    int fromIdx = Math.min(page * safeSize, total);
                    int toIdx = Math.min(fromIdx + safeSize, total);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("totalElements", total);
                    r.put("page", page);
                    r.put("size", safeSize);
                    r.put("content", events.subList(fromIdx, toIdx));
                    return r;
                });
    }

    public Mono<Map<String, Object>> getTradeEventDetail(UUID masterId, String eventId) {
        return copyLogs.findByCopyGroupId(eventId)
                .filter(l -> masterId.equals(l.getMasterId()))
                .collectList()
                .flatMap(logs -> {
                    if (logs.isEmpty()) return Mono.empty();
                    Map<String, Object> detail = toEventSummary(eventId, logs);
                    return users.findById(masterId)
                            .flatMapMany(master -> reactor.core.publisher.Flux.fromIterable(logs)
                                    .flatMap(l -> users.findById(l.getChildId())
                                            .map(child -> toChildRow(l, child.getName()))))
                            .collectList()
                            .map(children -> {
                                detail.put("children", children);
                                return detail;
                            });
                });
    }

    public Mono<Map<String, Object>> getLatencyStats(UUID masterId, int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        return copyLogs.findByMasterId(masterId)
                .filter(l -> l.getCreatedAt() != null && l.getCreatedAt().isAfter(since))
                .collectList()
                .map(logs -> {
                    List<Long> latencies = logs.stream()
                            .filter(l -> l.getLatencyMs() != null && l.getLatencyMs() > 0)
                            .map(CopyLog::getLatencyMs)
                            .sorted()
                            .toList();
                    long success = logs.stream().filter(l -> "SUCCESS".equals(l.getChildStatus())).count();
                    long total = logs.size();
                    Map<String, Object> r = new LinkedHashMap<>();
                    if (latencies.isEmpty()) {
                        r.put("tradeCount", total);
                        r.put("successRate", total > 0 ? roundPct(success * 100.0 / total) : 0);
                        r.put("brokerBreakdown", List.of());
                        return r;
                    }
                    r.put("avgTotalLatencyMs", avg(latencies));
                    r.put("minTotalLatencyMs", latencies.get(0));
                    r.put("maxTotalLatencyMs", latencies.get(latencies.size() - 1));
                    r.put("p50LatencyMs", percentile(latencies, 50));
                    r.put("p95LatencyMs", percentile(latencies, 95));
                    r.put("p99LatencyMs", percentile(latencies, 99));
                    r.put("tradeCount", total);
                    r.put("successRate", total > 0 ? roundPct(success * 100.0 / total) : 0);
                    r.put("brokerBreakdown", List.of());
                    return r;
                });
    }

    public Mono<List<Map<String, Object>>> getChildTimeline(UUID childId) {
        return copyLogs.findByChildId(childId)
                .flatMap(l -> users.findById(l.getMasterId())
                        .map(master -> {
                            Map<String, Object> t = new LinkedHashMap<>();
                            t.put("eventId", l.getCopyGroupId());
                            t.put("masterName", master.getName());
                            t.put("symbol", l.getSymbol());
                            t.put("side", l.getTradeType());
                            t.put("masterTriggeredAt", l.getMasterPlacedAt() != null
                                    ? l.getMasterPlacedAt().toString() : l.getEngineReceivedAt());
                            t.put("myOrderPlacedAt", l.getChildPlacedAt() != null
                                    ? l.getChildPlacedAt().toString() : l.getCreatedAt());
                            t.put("totalChildLatencyMs", l.getLatencyMs());
                            t.put("status", l.getChildStatus());
                            t.put("skipReason", l.getSkipReason());
                            t.put("errorMessage", l.getErrorMessage());
                            t.put("failureReason", l.getErrorMessage());
                            t.put("masterStatus", l.getMasterStatus());
                            t.put("qty", l.getQty());
                            t.put("masterQty", l.getQty());
                            t.put("orderId", l.getMasterTradeId());
                            return t;
                        }))
                .collectList()
                .map(list -> list.stream()
                        .sorted((a, b) -> String.valueOf(b.get("myOrderPlacedAt"))
                                .compareTo(String.valueOf(a.get("myOrderPlacedAt"))))
                        .toList());
    }

    private static boolean filterLog(CopyLog l, String from, String to, String symbol, String side) {
        if (symbol != null && !symbol.isBlank() && (l.getSymbol() == null || !l.getSymbol().equalsIgnoreCase(symbol))) {
            return false;
        }
        if (side != null && !side.isBlank() && (l.getTradeType() == null || !l.getTradeType().equalsIgnoreCase(side))) {
            return false;
        }
        if (l.getCreatedAt() == null) return true;
        try {
            if (from != null && !from.isBlank() && l.getCreatedAt().isBefore(Instant.parse(from + "T00:00:00Z"))) {
                return false;
            }
            if (to != null && !to.isBlank() && l.getCreatedAt().isAfter(Instant.parse(to + "T23:59:59Z"))) {
                return false;
            }
        } catch (Exception ignored) { /* ignore bad date filter */ }
        return true;
    }

    private static Map<String, Object> toEventSummary(String eventId, List<CopyLog> logs) {
        CopyLog first = logs.get(0);
        long succeeded = logs.stream().filter(l -> "SUCCESS".equals(l.getChildStatus())).count();
        long failed = logs.stream().filter(l -> "FAILED".equals(l.getChildStatus())).count();
        long skipped = logs.stream().filter(l -> "SKIPPED".equals(l.getChildStatus())).count();
        List<Long> childLat = logs.stream()
                .map(CopyLog::getLatencyMs)
                .filter(Objects::nonNull)
                .filter(ms -> ms > 0)
                .toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", eventId);
        m.put("symbol", first.getSymbol());
        m.put("side", first.getTradeType());
        m.put("masterQty", first.getQty());
        m.put("masterTriggeredAt", first.getMasterPlacedAt() != null
                ? first.getMasterPlacedAt().toString() : first.getEngineReceivedAt());
        m.put("engineReceivedAt", first.getEngineReceivedAt());
        if (!childLat.isEmpty()) {
            m.put("avgChildLatencyMs", avg(childLat));
            m.put("minChildLatencyMs", Collections.min(childLat));
            m.put("maxChildLatencyMs", Collections.max(childLat));
        }
        m.put("childrenTotal", logs.size());
        m.put("childrenSucceeded", succeeded);
        m.put("childrenFailed", failed);
        m.put("childrenSkipped", skipped);
        m.put("failures", logs.stream()
                .filter(l -> "FAILED".equals(l.getChildStatus()) || "SKIPPED".equals(l.getChildStatus()))
                .map(l -> {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("childId", l.getChildId() != null ? l.getChildId().toString() : null);
                    f.put("status", l.getChildStatus());
                    f.put("errorMessage", l.getErrorMessage());
                    f.put("skipReason", l.getSkipReason());
                    f.put("latencyMs", l.getLatencyMs());
                    return f;
                }).toList());
        return m;
    }

    private static Map<String, Object> toChildRow(CopyLog l, String childName) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("childName", childName);
        c.put("childId", l.getChildId() != null ? l.getChildId().toString() : null);
        c.put("broker", "—");
        c.put("status", l.getChildStatus());
        c.put("symbol", l.getSymbol());
        c.put("side", l.getTradeType());
        c.put("qty", l.getQty());
        c.put("masterQty", l.getQty());
        c.put("orderId", l.getMasterTradeId());
        c.put("errorMessage", l.getErrorMessage());
        c.put("skipReason", l.getSkipReason());
        c.put("failureReason", l.getErrorMessage() != null ? l.getErrorMessage() : l.getSkipReason());
        c.put("totalChildLatencyMs", l.getLatencyMs());
        c.put("brokerLatencyMs", l.getLatencyMs());
        c.put("childPlacedAt", l.getChildPlacedAt() != null ? l.getChildPlacedAt().toString() : null);
        return c;
    }

    private static long avg(List<Long> values) {
        return values.isEmpty() ? 0 : values.stream().mapToLong(Long::longValue).sum() / values.size();
    }

    private static long percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) return 0;
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(pct / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }

    private static double roundPct(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
