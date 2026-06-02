package com.copytrading.trading;

import com.copytrading.broker.BrokerAccount;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.BrokerAccountService;
import com.copytrading.engine.OrderNormalizer;
import com.copytrading.logs.CopyLog;
import com.copytrading.logs.CopyLogRepository;
import com.copytrading.master.MasterActiveAccountRepository;
import com.copytrading.broker.dhan.DhanResponseParser;
import com.copytrading.positions.PositionDto;
import com.copytrading.positions.PositionsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/** Open book, open options, and option-status views for master/child dashboards. */
@Service
public class TradingDataService {

    private final BrokerAccountRepository brokerRepo;
    private final MasterActiveAccountRepository activeAccountRepo;
    private final BrokerAccountService brokerService;
    private final PositionsService positionsService;
    private final CopyLogRepository copyLogs;
    private final ObjectMapper objectMapper;

    public TradingDataService(BrokerAccountRepository brokerRepo,
                              MasterActiveAccountRepository activeAccountRepo,
                              BrokerAccountService brokerService,
                              PositionsService positionsService,
                              CopyLogRepository copyLogs,
                              ObjectMapper objectMapper) {
        this.brokerRepo = brokerRepo;
        this.activeAccountRepo = activeAccountRepo;
        this.brokerService = brokerService;
        this.positionsService = positionsService;
        this.copyLogs = copyLogs;
        this.objectMapper = objectMapper;
    }

    public Mono<Map<String, Object>> getOpenBook(UUID userId, boolean master) {
        return resolveAccount(userId, master)
                .flatMap(a -> brokerService.getOrders(a.getId(), userId)
                        .map(resp -> {
                            List<Map<String, Object>> all = toOrderList(resp.get("orders"));
                            List<Map<String, Object>> open = all.stream()
                                    .filter(o -> !isTerminalOrder(OrderNormalizer.extractStatus(o)))
                                    .toList();
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("orders", open);
                            r.put("total", open.size());
                            r.put("brokerAccountId", a.getId().toString());
                            r.put("broker", a.getBrokerId());
                            if (resp.containsKey("error")) r.put("error", resp.get("error"));
                            if (resp.containsKey("errorCode")) r.put("errorCode", resp.get("errorCode"));
                            return r;
                        }))
                .switchIfEmpty(Mono.just(Map.of(
                        "orders", List.of(),
                        "total", 0,
                        "error", "No active broker session. Login to your broker first.",
                        "errorCode", "SESSION_EXPIRED",
                        "action", "RE_LOGIN")));
    }

    public Mono<Map<String, Object>> getOpenOptions(UUID userId, boolean master) {
        Mono<Map<String, Object>> positionsMono = master
                ? positionsService.getMasterPositions(userId)
                : positionsService.getChildPositions(userId);
        return positionsMono.map(pos -> {
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) pos.getOrDefault("positions", List.of());
            List<Object> options = raw.stream().filter(this::isOptionPosition).toList();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("positions", options);
            r.put("total", options.size());
            r.put("totalPnl", options.stream()
                    .mapToDouble(p -> p instanceof PositionDto d ? d.getPnl() : 0)
                    .sum());
            if (pos.containsKey("error")) r.put("error", pos.get("error"));
            if (pos.containsKey("brokerAccountId")) r.put("brokerAccountId", pos.get("brokerAccountId"));
            return r;
        });
    }

    public Mono<Map<String, Object>> getOptionStatus(UUID userId, boolean master) {
        var logsFlux = master ? copyLogs.findByMasterId(userId) : copyLogs.findByChildId(userId);
        return logsFlux.collectList().map(logs -> {
            List<Map<String, Object>> items = logs.stream()
                    .filter(l -> isOptionSymbol(l.getSymbol()))
                    .sorted(Comparator.comparing(CopyLog::getCreatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(this::toOptionStatusRow)
                    .toList();
            long success = items.stream().filter(i -> "SUCCESS".equals(i.get("status"))).count();
            long failed = items.stream().filter(i -> "FAILED".equals(i.get("status"))).count();
            long skipped = items.stream().filter(i -> "SKIPPED".equals(i.get("status"))).count();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("items", items);
            r.put("total", items.size());
            r.put("success", success);
            r.put("failed", failed);
            r.put("skipped", skipped);
            return r;
        });
    }

    private Map<String, Object> toOptionStatusRow(CopyLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("copyGroupId", l.getCopyGroupId());
        m.put("symbol", l.getSymbol());
        m.put("side", l.getTradeType());
        m.put("qty", l.getQty());
        m.put("childQty", l.getChildQty());
        m.put("masterQty", l.getQty());
        m.put("status", l.getChildStatus());
        m.put("childStatus", l.getChildStatus());
        m.put("masterStatus", l.getMasterStatus());
        m.put("errorMessage", l.getErrorMessage());
        m.put("skipReason", l.getSkipReason());
        m.put("failureReason", l.getErrorMessage() != null ? l.getErrorMessage() : l.getSkipReason());
        m.put("latencyMs", l.getLatencyMs());
        m.put("masterId", l.getMasterId() != null ? l.getMasterId().toString() : null);
        m.put("childId", l.getChildId() != null ? l.getChildId().toString() : null);
        m.put("orderId", l.getMasterTradeId());
        m.put("childBrokerOrderId", l.getChildBrokerOrderId());
        m.put("createdAt", l.getCreatedAt() != null ? l.getCreatedAt().toString() : null);
        m.put("childPlacedAt", l.getChildPlacedAt() != null ? l.getChildPlacedAt().toString() : null);
        m.put("masterPlacedAt", l.getMasterPlacedAt() != null ? l.getMasterPlacedAt().toString() : null);
        applyOptionDetails(m, l.getSymbol());
        return m;
    }

    private static void applyOptionDetails(Map<String, Object> m, String symbol) {
        if (symbol == null || symbol.isBlank()) return;
        String u = symbol.toUpperCase();
        m.put("instrumentType", u.contains("FUT") ? "FUT" : (u.endsWith("CE") || u.contains("CE") ? "CE" : (u.endsWith("PE") || u.contains("PE") ? "PE" : "OPT")));
        m.put("isOption", u.contains("CE") || u.contains("PE") || u.contains("OPT"));
        m.put("isFuture", u.contains("FUT"));
        // NIFTY25JUN25000CE style
        java.util.regex.Matcher opt = java.util.regex.Pattern
                .compile("([A-Z]+)(\\d{2}[A-Z]{3})(\\d+)(CE|PE)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(u.replace(" ", ""));
        if (opt.find()) {
            m.put("underlying", opt.group(1));
            m.put("expiry", opt.group(2));
            m.put("strike", opt.group(3));
            m.put("optionType", opt.group(4));
        }
    }

    private Mono<BrokerAccount> resolveAccount(UUID userId, boolean master) {
        if (master) {
            return activeAccountRepo.findById(userId)
                    .flatMap(aa -> brokerRepo.findById(aa.getBrokerAccountId()))
                    .switchIfEmpty(brokerRepo.findByUserId(userId)
                            .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                            .next());
        }
        return brokerRepo.findByUserId(userId)
                .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                .next();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toOrderList(Object ordersObj) {
        if (ordersObj == null) {
            return List.of();
        }
        if (ordersObj instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(o -> (Map<String, Object>) o)
                    .collect(Collectors.toList());
        }
        if (ordersObj instanceof String s && !s.isBlank()) {
            return DhanResponseParser.parseListPayload(s, objectMapper);
        }
        if (ordersObj instanceof Map<?, ?> map) {
            for (String key : List.of("order_list", "orderBook", "data", "orders")) {
                Object inner = map.get(key);
                List<Map<String, Object>> parsed = toOrderList(inner);
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
        }
        return List.of();
    }

    private static boolean isTerminalOrder(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.trim().toUpperCase();
        return s.contains("COMPLETE") || s.contains("CANCEL") || s.contains("REJECT")
                || "EXECUTED".equals(s) || "TRADED".equals(s);
    }

    private boolean isOptionPosition(Object p) {
        if (p instanceof PositionDto dto) {
            return isOptionSymbol(dto.getSymbol());
        }
        if (p instanceof Map<?, ?> m) {
            Object sym = m.get("symbol");
            return isOptionSymbol(sym != null ? sym.toString() : null);
        }
        return false;
    }

    static boolean isOptionSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        String u = symbol.toUpperCase();
        return u.contains(" CE ") || u.contains(" PE ") || u.contains("CE ") || u.contains("PE ")
                || u.endsWith("CE") || u.endsWith("PE") || u.contains("FUT") || u.contains("OPT")
                || u.contains("NIFTY") && (u.contains("CE") || u.contains("PE"));
    }
}
