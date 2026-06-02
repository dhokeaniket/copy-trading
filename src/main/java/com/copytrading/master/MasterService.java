package com.copytrading.master;

import com.copytrading.auth.UserAccountRepository;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.BrokerAccountService;
import com.copytrading.broker.BrokerProfileService;
import com.copytrading.config.EnginePollingProperties;
import com.copytrading.engine.OrderPollingService;
import com.copytrading.logs.CopyLog;
import com.copytrading.logs.CopyLogRepository;
import com.copytrading.logs.TradeLogRepository;
import com.copytrading.positions.PositionsService;
import com.copytrading.subscription.Subscription;
import com.copytrading.subscription.SubscriptionRepository;
import com.copytrading.trade.TradeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class MasterService {

    private final SubscriptionRepository subs;
    private final UserAccountRepository users;
    private final TradeLogRepository logs;
    private final CopyLogRepository copyLogs;
    private final MasterActiveAccountRepository activeAccountRepo;
    private final BrokerAccountRepository brokerRepo;
    private final BrokerAccountService brokerService;
    private final BrokerProfileService brokerProfileService;
    private final PositionsService positionsService;
    private final OrderPollingService orderPollingService;
    private final EnginePollingProperties pollingProperties;
    private final DatabaseClient db;
    private final TradeRepository tradeRepository;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public MasterService(SubscriptionRepository subs, UserAccountRepository users,
                         TradeLogRepository logs, CopyLogRepository copyLogs,
                         MasterActiveAccountRepository activeAccountRepo,
                         BrokerAccountRepository brokerRepo,
                         BrokerAccountService brokerService,
                         BrokerProfileService brokerProfileService,
                         PositionsService positionsService,
                         OrderPollingService orderPollingService,
                         EnginePollingProperties pollingProperties,
                         DatabaseClient db,
                         TradeRepository tradeRepository) {
        this.subs = subs;
        this.users = users;
        this.logs = logs;
        this.copyLogs = copyLogs;
        this.activeAccountRepo = activeAccountRepo;
        this.brokerRepo = brokerRepo;
        this.brokerService = brokerService;
        this.brokerProfileService = brokerProfileService;
        this.positionsService = positionsService;
        this.orderPollingService = orderPollingService;
        this.pollingProperties = pollingProperties;
        this.db = db;
        this.tradeRepository = tradeRepository;
    }

    private Mono<MasterPnlCalculator.PnlSnapshot> buildPnlSnapshot(UUID masterId) {
        Mono<List<com.copytrading.trade.Trade>> tradesMono =
                tradeRepository.findByUserIdOrderByPlacedAtDesc(masterId).collectList();
        Mono<List<CopyLog>> logsMono = copyLogs.findByMasterId(masterId).collectList();
        Mono<Map<String, Object>> posMono = positionsService.getMasterPositions(masterId)
                .onErrorReturn(Map.of("totalPnl", 0));
        Mono<Map<String, Object>> marginMono = activeAccountRepo.findById(masterId)
                .flatMap(aa -> brokerService.getMargin(aa.getBrokerAccountId(), masterId))
                .onErrorReturn(Map.of())
                .defaultIfEmpty(Map.of());
        return Mono.zip(tradesMono, logsMono, posMono, marginMono)
                .map(t -> MasterPnlCalculator.build(
                        t.getT1(),
                        t.getT2(),
                        MasterChildMetricsHelper.toDouble(t.getT3().get("totalPnl")),
                        t.getT4()));
    }

    // Active account management (DB-backed, uses upsert)
    public Mono<Map<String, Object>> setActiveAccount(UUID masterId, UUID brokerAccountId) {
        return db.sql("INSERT INTO master_active_accounts (master_id, broker_account_id, activated_at) " +
                       "VALUES (:mid, :bid, now()) " +
                       "ON CONFLICT (master_id) DO UPDATE SET broker_account_id = :bid, activated_at = now()")
                .bind("mid", masterId)
                .bind("bid", brokerAccountId)
                .then()
                .thenReturn(Map.<String, Object>of(
                        "brokerAccountId", brokerAccountId.toString(),
                        "message", "Active account set"))
                .onErrorResume(e -> {
                    Map<String, Object> fallback = new LinkedHashMap<>();
                    fallback.put("error", "Could not set active account: " + e.getMessage());
                    return Mono.just(fallback);
                });
    }

    public Mono<Map<String, Object>> getActiveAccount(UUID masterId) {
        return activeAccountRepo.findById(masterId)
                .flatMap(a -> enrichActiveAccount(masterId, a.getBrokerAccountId()))
                .switchIfEmpty(Mono.fromCallable(() -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("brokerAccountId", "");
                    r.put("connected", false);
                    r.put("message", "No master account connected. Engine has nothing to poll.");
                    return r;
                }))
                .onErrorResume(e -> {
                    Map<String, Object> fallback = new LinkedHashMap<>();
                    fallback.put("brokerAccountId", "");
                    fallback.put("connected", false);
                    fallback.put("message", "No active account set");
                    return Mono.just(fallback);
                });
    }

    private Mono<Map<String, Object>> enrichActiveAccount(UUID masterId, UUID brokerAccountId) {
        return brokerRepo.findById(brokerAccountId)
                .filter(a -> a.getUserId().equals(masterId))
                .flatMap(a -> brokerProfileService.getProfile(brokerAccountId, masterId, false)
                        .map(profile -> {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("brokerAccountId", brokerAccountId.toString());
                            r.put("connected", true);
                            r.put("broker", a.getBrokerId());
                            r.put("nickname", a.getNickname());
                            r.put("clientId", a.getClientId());
                            r.put("sessionActive", profile.getOrDefault("sessionActive", a.isSessionActive()));
                            r.put("isTokenExpired", profile.getOrDefault("isTokenExpired", false));
                            r.put("marginAvailable", profile.getOrDefault("marginAvailable", 0));
                            r.put("marginUsed", profile.getOrDefault("marginUsed", 0));
                            r.put("margin", profile.getOrDefault("marginAvailable", 0));
                            r.put("marginUsedPercent", profile.getOrDefault("marginUsedPercent", 0));
                            r.put("fundsUtilizationStatus", profile.getOrDefault("fundsUtilizationStatus", "GREEN"));
                            r.put("openPositionsCount", profile.getOrDefault("openPositionsCount", 0));
                            r.put("message", "Active account set");
                            return r;
                        }))
                .switchIfEmpty(Mono.just(Map.of(
                        "brokerAccountId", brokerAccountId.toString(),
                        "connected", false,
                        "message", "Active account not found")));
    }

    public Mono<Map<String, String>> clearActiveAccount(UUID masterId) {
        return activeAccountRepo.deleteById(masterId)
                .thenReturn(Map.of("message", "Active account cleared"))
                .onErrorResume(e -> Mono.just(Map.of("message", "Active account cleared")));
    }

    // Copy logs scoped to master
    public Mono<Map<String, Object>> getCopyLogs(UUID masterId) {
        return copyLogs.findByMasterId(masterId).collectList().flatMap(logsList -> {
            // Resolve child names
            Set<UUID> childIds = logsList.stream().map(l -> l.getChildId()).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            return reactor.core.publisher.Flux.fromIterable(childIds)
                    .flatMap(cid -> users.findById(cid).map(u -> Map.entry(cid, u.getName())))
                    .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                    .defaultIfEmpty(Map.of())
                    .map(childNames -> {
                        var logs = logsList.stream().map(l -> {
                            Map<String, Object> m = new java.util.LinkedHashMap<>();
                            m.put("id", l.getId());
                            m.put("masterId", l.getMasterId() != null ? l.getMasterId().toString() : null);
                            m.put("childId", l.getChildId() != null ? l.getChildId().toString() : null);
                            m.put("childName", childNames.getOrDefault(l.getChildId(), "Unknown"));
                            m.put("symbol", l.getSymbol());
                            m.put("qty", l.getQty());
                            m.put("childQty", l.getChildQty());
                            m.put("tradeType", l.getTradeType());
                            m.put("side", l.getTradeType());
                            m.put("product", l.getProduct());
                            m.put("orderType", l.getOrderType());
                            m.put("price", l.getPrice());
                            m.put("triggerPrice", l.getTriggerPrice());
                            m.put("masterStatus", l.getMasterStatus());
                            m.put("childStatus", l.getChildStatus());
                            m.put("status", l.getChildStatus());
                            m.put("errorMessage", l.getErrorMessage());
                            m.put("skipReason", l.getSkipReason());
                            m.put("failureReason", l.getErrorMessage() != null ? l.getErrorMessage() : l.getSkipReason());
                            m.put("latencyMs", l.getLatencyMs());
                            m.put("copyGroupId", l.getCopyGroupId());
                            m.put("orderId", l.getMasterTradeId());
                            m.put("masterTradeId", l.getMasterTradeId());
                            m.put("childBrokerOrderId", l.getChildBrokerOrderId());
                            m.put("engineReceivedAt", l.getEngineReceivedAt() != null ? l.getEngineReceivedAt().toString() : null);
                            m.put("masterPlacedAt", l.getMasterPlacedAt() != null ? l.getMasterPlacedAt().toString() : null);
                            m.put("childPlacedAt", l.getChildPlacedAt() != null ? l.getChildPlacedAt().toString() : null);
                            m.put("createdAt", l.getCreatedAt() != null ? l.getCreatedAt().toString() : null);
                            return m;
                        }).toList();
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("logs", logs);
                        out.put("total", logs.size());
                        return out;
                    });
        }).onErrorResume(e -> Mono.just(Map.of("logs", List.of(), "total", 0)));
    }

    // Earnings (with monthly breakdown + payouts)
    public Mono<Map<String, Object>> getEarnings(UUID masterId) {
        return subs.findByMasterIdAndCopyingStatus(masterId, "ACTIVE").collectList().map(activeSubs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("totalEarnings", 0);
            r.put("thisMonth", 0);
            r.put("lastMonth", 0);
            r.put("pendingPayout", 0);
            r.put("currency", "INR");
            r.put("monthlyBreakdown", List.of(
                    Map.of("month", "2025-01", "subscribers", activeSubs.size(), "subscriptionFee", 0, "performanceBonus", 0, "total", 0),
                    Map.of("month", "2025-02", "subscribers", activeSubs.size(), "subscriptionFee", 0, "performanceBonus", 0, "total", 0),
                    Map.of("month", "2025-03", "subscribers", activeSubs.size(), "subscriptionFee", 0, "performanceBonus", 0, "total", 0),
                    Map.of("month", "2025-04", "subscribers", activeSubs.size(), "subscriptionFee", 0, "performanceBonus", 0, "total", 0),
                    Map.of("month", "2025-05", "subscribers", activeSubs.size(), "subscriptionFee", 0, "performanceBonus", 0, "total", 0),
                    Map.of("month", "2025-06", "subscribers", activeSubs.size(), "subscriptionFee", 0, "performanceBonus", 0, "total", 0)
            ));
            r.put("payouts", List.of());
            return r;
        });
    }

    // Payouts (mock)
    public Mono<Map<String, Object>> getPayouts(UUID masterId) {
        return Mono.just(Map.<String, Object>of("payouts", List.of(), "totalPaid", 0, "currency", "INR"));
    }

    // 4.1 List children (with live margin + P&L for follower table)
    // Excludes master's own ID from children list and shows only ACTIVE/PAUSED/PENDING_APPROVAL
    public Mono<Map<String, Object>> listChildren(UUID masterId) {
        return subs.findByMasterId(masterId)
                .filter(s -> !s.getChildId().equals(masterId)) // exclude master's own ID
                .filter(s -> "ACTIVE".equals(s.getCopyingStatus())
                        || "PAUSED".equals(s.getCopyingStatus())
                        || "PENDING_APPROVAL".equals(s.getCopyingStatus())) // only active subscriptions
                .flatMap(s -> buildFollowerRow(s), 2)
                .collectList()
                .map(list -> Map.of("children", (Object) list));
    }

    /** All-in-one payload for master Copy Trading page. */
    public Mono<Map<String, Object>> getCopyTradingPage(UUID masterId) {
        return Mono.zip(
                getActiveAccount(masterId),
                listChildren(masterId),
                getDashboard(masterId),
                copyLogs.findByMasterId(masterId).collectList()
        ).map(t -> {
            Map<String, Object> active = t.getT1();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) t.getT2().get("children");
            Map<String, Object> dashboard = t.getT3();
            List<CopyLog> allLogs = t.getT4();

            List<Map<String, Object>> alerts = buildLowMarginAlerts(children);
            if (!Boolean.TRUE.equals(active.get("connected")) || active.get("brokerAccountId") == null
                    || "".equals(String.valueOf(active.getOrDefault("brokerAccountId", "")))) {
                alerts.add(0, Map.of(
                        "level", "ERROR",
                        "code", "NO_MASTER_ACCOUNT",
                        "message", "No master account connected. Engine has nothing to poll."));
            } else if (Boolean.FALSE.equals(active.get("sessionActive"))
                    || Boolean.TRUE.equals(active.get("isTokenExpired"))) {
                alerts.add(0, Map.of(
                        "level", "ERROR",
                        "code", "MASTER_SESSION_EXPIRED",
                        "message", "Master broker session expired — reconnect to resume polling.",
                        "broker", active.get("broker")));
            }

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("activeAccount", active);
            r.put("children", children);
            r.put("dashboard", dashboard);
            r.put("alerts", alerts);
            r.put("pollingEnabled", orderPollingService.isPollingEnabled());
            r.put("pollingIntervalMs", pollingProperties.getIntervalMs());
            r.put("replicationActive", orderPollingService.isPollingEnabled()
                    && Boolean.TRUE.equals(active.get("connected"))
                    && Boolean.TRUE.equals(active.getOrDefault("sessionActive", false)));
            r.put("todayCopiesSuccess", countTodaySuccess(allLogs));
            return r;
        });
    }

    /** Master P&L analytics — aggregate + per-child + 7-day copy activity chart. */
    public Mono<Map<String, Object>> getPnlAnalytics(UUID masterId) {
        Instant startOfDay = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        return Mono.zip(
                getActiveAccount(masterId),
                listChildren(masterId),
                positionsService.getMasterPositions(masterId).onErrorReturn(Map.of("totalPnl", 0, "positions", List.of())),
                copyLogs.findByMasterId(masterId).collectList(),
                buildPnlSnapshot(masterId)
        ).map(t -> {
            Map<String, Object> active = t.getT1();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) t.getT2().get("children");
            Map<String, Object> masterPos = t.getT3();
            List<CopyLog> logs = t.getT4();
            MasterPnlCalculator.PnlSnapshot snap = t.getT5();

            double masterUnrealized = MasterChildMetricsHelper.toDouble(masterPos.get("totalPnl"));
            double followersUnrealized = children.stream()
                    .mapToDouble(c -> MasterChildMetricsHelper.toDouble(c.get("pnlToday")))
                    .sum();
            double totalMarginAvailable = children.stream()
                    .mapToDouble(c -> MasterChildMetricsHelper.toDouble(c.get("marginAvailable")))
                    .sum();

            long success = logs.stream().filter(l -> "SUCCESS".equals(l.getChildStatus())).count();
            long failed = logs.stream().filter(l -> "FAILED".equals(l.getChildStatus())).count();
            long todaySuccess = logs.stream()
                    .filter(l -> l.getCreatedAt() != null && !l.getCreatedAt().isBefore(startOfDay))
                    .filter(l -> "SUCCESS".equals(l.getChildStatus()))
                    .count();

            Map<String, Object> summary = new LinkedHashMap<>();
            double combinedUnrealized = masterUnrealized + followersUnrealized;
            summary.put("masterUnrealizedPnl", MasterChildMetricsHelper.round2(masterUnrealized));
            summary.put("followersUnrealizedPnl", MasterChildMetricsHelper.round2(followersUnrealized));
            summary.put("combinedUnrealizedPnl", MasterChildMetricsHelper.round2(combinedUnrealized));
            MasterPnlCalculator.applySummaryAliases(summary, snap);
            summary.put("totalUnrealisedPnl", MasterChildMetricsHelper.round2(combinedUnrealized));
            summary.put("totalUnrealizedPnl", MasterChildMetricsHelper.round2(combinedUnrealized));
            summary.put("todayPnl", MasterChildMetricsHelper.round2(snap.todayRealised() + masterUnrealized));
            summary.put("totalFollowerMarginAvailable", MasterChildMetricsHelper.round2(totalMarginAvailable));
            summary.put("activeFollowers", children.stream().filter(c -> "ACTIVE".equals(c.get("copyingStatus"))).count());
            summary.put("totalCopiesSuccess", success);
            summary.put("totalCopiesFailed", failed);
            summary.put("todayCopiesSuccess", todaySuccess);
            summary.put("replicationSuccessRate", success + failed > 0
                    ? Math.round(success * 100.0 / (success + failed)) : 0);

            List<Map<String, Object>> dailyChart = snap.dailyPnl();
            if (dailyChart.size() > 7) {
                dailyChart = dailyChart.subList(dailyChart.size() - 7, dailyChart.size());
            }

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("summary", summary);
            r.put("masterActiveAccount", active);
            r.put("masterPositions", masterPos.getOrDefault("positions", List.of()));
            r.put("childPerformance", children.stream().map(c -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("childId", c.get("childId"));
                p.put("name", c.get("name"));
                p.put("marginAvailable", c.get("marginAvailable"));
                p.put("pnlToday", c.get("pnlToday"));
                p.put("pnl", c.getOrDefault("pnlToday", 0));
                p.put("totalPnL", c.getOrDefault("pnlToday", 0));
                p.put("openPositionsCount", c.get("openPositionsCount"));
                p.put("scalingFactor", c.get("scalingFactor"));
                p.put("copyingStatus", c.get("copyingStatus"));
                p.put("tradesCopied", c.getOrDefault("tradesCopied", 0));
                return p;
            }).toList());
            r.put("dailyChart", dailyChart);
            r.put("monthlyPnl", snap.monthlyPnl());
            r.put("earningsBreakdown", List.of(
                    Map.of("name", "Replication volume", "value", success, "unit", "trades"),
                    Map.of("name", "Follower margin (total)", "value", totalMarginAvailable, "unit", "INR")
            ));
            return r;
        });
    }

    private Mono<Map<String, Object>> buildFollowerRow(Subscription s) {
        return users.findById(s.getChildId())
                .defaultIfEmpty(new com.copytrading.auth.UserAccount()) // fallback if user not found
                .flatMap(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("childId", s.getChildId().toString());
            m.put("name", u.getName() != null ? u.getName() : "Unknown");
            m.put("email", u.getEmail() != null ? u.getEmail() : "");
            m.put("scalingFactor", s.getScalingFactor());
            m.put("multiplier", s.getScalingFactor());
            m.put("copyingStatus", s.getCopyingStatus());
            m.put("status", s.getCopyingStatus());
            m.put("subscribedAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            m.put("brokerAccountId", s.getBrokerAccountId() != null ? s.getBrokerAccountId().toString() : null);
            m.put("copySides", s.getCopySides() != null ? s.getCopySides() : "BUY_ONLY");

            if (s.getBrokerAccountId() == null) {
                MasterChildMetricsHelper.applyZeroMargin(m, "Child has no broker account linked");
                return Mono.just(m);
            }
            UUID childId = s.getChildId();
            UUID accountId = s.getBrokerAccountId();
            return brokerRepo.findById(accountId)
                    .flatMap(acc -> Mono.zip(
                            brokerService.getMargin(accountId, childId)
                                    .onErrorReturn(Map.of("availableMargin", 0, "usedMargin", 0, "totalFunds", 0)),
                            positionsService.getPositionsForAccount(accountId)
                                    .onErrorReturn(List.of()),
                            copyLogs.findByMasterIdAndChildId(s.getMasterId(), childId).collectList()
                    ).map(tuple -> {
                        m.put("broker", acc.getBrokerId());
                        m.put("brokerAccountNickname", acc.getNickname());
                        MasterChildMetricsHelper.applyMarginAndPnl(m, tuple.getT1(), tuple.getT2(), acc.isSessionActive());
                        List<CopyLog> childLogs = tuple.getT3();
                        long copied = childLogs.stream().filter(l -> "SUCCESS".equals(l.getChildStatus())).count();
                        m.put("tradesCopied", copied);
                        return m;
                    }))
                    .switchIfEmpty(Mono.defer(() -> {
                        MasterChildMetricsHelper.applyZeroMargin(m, "Broker account not found");
                        return Mono.just(m);
                    }));
        });
    }

    private static List<Map<String, Object>> buildLowMarginAlerts(List<Map<String, Object>> children) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (Map<String, Object> c : children) {
            if (Boolean.TRUE.equals(c.get("lowMargin"))) {
                alerts.add(Map.of(
                        "level", "WARNING",
                        "code", "LOW_CHILD_MARGIN",
                        "childId", c.get("childId"),
                        "message", "Child '" + c.get("name") + "' has low margin (₹"
                                + c.getOrDefault("marginAvailable", 0) + "). Orders may be rejected."));
            }
            if (Boolean.FALSE.equals(c.get("sessionActive"))) {
                alerts.add(Map.of(
                        "level", "WARNING",
                        "code", "CHILD_SESSION_EXPIRED",
                        "childId", c.get("childId"),
                        "message", "Child '" + c.get("name") + "' broker session expired — ask them to re-login."));
            }
        }
        return alerts;
    }

    private static long countTodaySuccess(List<CopyLog> logs) {
        Instant start = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        return logs.stream()
                .filter(l -> l.getCreatedAt() != null && !l.getCreatedAt().isBefore(start))
                .filter(l -> "SUCCESS".equals(l.getChildStatus()))
                .count();
    }

    private static List<Map<String, Object>> buildDailyCopyChart(List<CopyLog> logs, int days) {
        LocalDate today = LocalDate.now(IST);
        List<Map<String, Object>> chart = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            Instant start = d.atStartOfDay(IST).toInstant();
            Instant end = d.plusDays(1).atStartOfDay(IST).toInstant();
            long success = logs.stream()
                    .filter(l -> l.getCreatedAt() != null
                            && !l.getCreatedAt().isBefore(start)
                            && l.getCreatedAt().isBefore(end))
                    .filter(l -> "SUCCESS".equals(l.getChildStatus()))
                    .count();
            long failed = logs.stream()
                    .filter(l -> l.getCreatedAt() != null
                            && !l.getCreatedAt().isBefore(start)
                            && l.getCreatedAt().isBefore(end))
                    .filter(l -> "FAILED".equals(l.getChildStatus()))
                    .count();
            chart.add(Map.of(
                    "date", d.toString(),
                    "copiesSuccess", success,
                    "copiesFailed", failed,
                    "value", success));
        }
        return chart;
    }

    // 4.2 Link child (master-initiated, bypasses approval)
    public Mono<Map<String, String>> linkChild(UUID masterId, UUID childId, Double scalingFactor) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .flatMap(existing -> {
                    // If pending, approve it
                    if ("PENDING_APPROVAL".equals(existing.getCopyingStatus())) {
                        existing.setCopyingStatus("ACTIVE");
                        existing.setApprovedOnce(true);
                        if (scalingFactor != null) existing.setScalingFactor(scalingFactor);
                        // If subscription already has brokerAccountId (from child's subscribe), keep it
                        if (existing.getBrokerAccountId() != null) {
                            return subs.save(existing).thenReturn(Map.of("message", "Child approved and linked"));
                        }
                        // Otherwise, auto-resolve child's first active broker account
                        return resolveChildBrokerAccount(childId)
                                .flatMap(brokerAccountId -> {
                                    existing.setBrokerAccountId(brokerAccountId);
                                    return subs.save(existing).thenReturn(Map.of("message", "Child approved and linked"));
                                })
                                .switchIfEmpty(subs.save(existing).thenReturn(
                                        Map.of("message", "Child approved but no broker account found. Child must link a broker account.")));
                    }
                    return Mono.<Map<String, String>>error(
                            new ResponseStatusException(HttpStatus.CONFLICT, "Child already linked"));
                })
                .switchIfEmpty(Mono.defer(() ->
                    // Brand new link from master — auto-resolve child's broker account
                    resolveChildBrokerAccount(childId)
                            .flatMap(brokerAccountId -> {
                                Subscription s = new Subscription();
                                s.setMasterId(masterId);
                                s.setChildId(childId);
                                s.setBrokerAccountId(brokerAccountId);
                                s.setScalingFactor(scalingFactor != null ? scalingFactor : 1.0);
                                s.setCopyingStatus("ACTIVE");
                                s.setApprovedOnce(true);
                                s.setCreatedAt(Instant.now());
                                return subs.save(s).thenReturn(Map.of("message", "Child linked successfully"));
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                // No broker account found — still create subscription, but warn
                                Subscription s = new Subscription();
                                s.setMasterId(masterId);
                                s.setChildId(childId);
                                s.setScalingFactor(scalingFactor != null ? scalingFactor : 1.0);
                                s.setCopyingStatus("ACTIVE");
                                s.setApprovedOnce(true);
                                s.setCreatedAt(Instant.now());
                                return subs.save(s).thenReturn(
                                        Map.of("message", "Child linked but no broker account found. Child must link a broker account for copy trading to work."));
                            }))
                ));
    }

    /**
     * Find the child's first active broker account (session active, has access token).
     * Falls back to any linked broker account if none are active.
     */
    private Mono<UUID> resolveChildBrokerAccount(UUID childId) {
        return brokerRepo.findByUserId(childId)
                .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                .next()
                .map(a -> a.getId())
                .switchIfEmpty(
                    brokerRepo.findByUserId(childId)
                            .next()
                            .map(a -> a.getId())
                );
    }

    // 4.2a List pending approval requests
    public Mono<Map<String, Object>> listPendingApprovals(UUID masterId) {
        return subs.findByMasterIdAndCopyingStatus(masterId, "PENDING_APPROVAL")
                .flatMap(s -> users.findById(s.getChildId()).map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("childId", s.getChildId());
                    m.put("name", u.getName());
                    m.put("email", u.getEmail());
                    m.put("requestedAt", s.getCreatedAt());
                    m.put("subscriptionId", s.getId());
                    return m;
                }))
                .collectList()
                .map(list -> Map.of("pendingApprovals", (Object) list));
    }

    // 4.2b Approve child subscription
    public Mono<Map<String, String>> approveChild(UUID masterId, UUID childId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                .flatMap(s -> {
                    if (!"PENDING_APPROVAL".equals(s.getCopyingStatus())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subscription is not pending approval"));
                    }
                    s.setCopyingStatus("ACTIVE");
                    s.setApprovedOnce(true);
                    return subs.save(s).thenReturn(Map.of("message", "Child approved"));
                });
    }

    // 4.2c Reject child subscription
    public Mono<Map<String, String>> rejectChild(UUID masterId, UUID childId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                .flatMap(s -> {
                    if (!"PENDING_APPROVAL".equals(s.getCopyingStatus())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subscription is not pending approval"));
                    }
                    s.setCopyingStatus("REJECTED");
                    return subs.save(s).thenReturn(Map.of("message", "Child rejected"));
                });
    }

    // 4.2b Bulk link children
    public Mono<Map<String, Object>> bulkLinkChildren(UUID masterId, List<Map<String, Object>> children) {
        return reactor.core.publisher.Flux.fromIterable(children)
                .flatMap(c -> {
                    UUID childId = UUID.fromString(c.get("childId").toString());
                    Double factor = c.containsKey("scalingFactor") && c.get("scalingFactor") != null
                            ? ((Number) c.get("scalingFactor")).doubleValue() : 1.0;
                    return subs.findByMasterIdAndChildId(masterId, childId)
                            .flatMap(existing -> {
                                if ("PENDING_APPROVAL".equals(existing.getCopyingStatus())) {
                                    existing.setCopyingStatus("ACTIVE");
                                    existing.setApprovedOnce(true);
                                    existing.setScalingFactor(factor);
                                    // Auto-resolve broker account if missing
                                    if (existing.getBrokerAccountId() == null) {
                                        return resolveChildBrokerAccount(childId)
                                                .flatMap(bid -> {
                                                    existing.setBrokerAccountId(bid);
                                                    return subs.save(existing);
                                                })
                                                .switchIfEmpty(subs.save(existing))
                                                .map(s -> Map.<String, Object>of(
                                                        "childId", childId.toString(), "status", "APPROVED", "subscriptionId", s.getId()));
                                    }
                                    return subs.save(existing).map(s -> Map.<String, Object>of(
                                            "childId", childId.toString(), "status", "APPROVED", "subscriptionId", s.getId()));
                                }
                                if ("INACTIVE".equals(existing.getCopyingStatus()) || "REJECTED".equals(existing.getCopyingStatus())) {
                                    existing.setCopyingStatus("ACTIVE");
                                    existing.setApprovedOnce(true);
                                    existing.setScalingFactor(factor);
                                    // Auto-resolve broker account if missing
                                    if (existing.getBrokerAccountId() == null) {
                                        return resolveChildBrokerAccount(childId)
                                                .flatMap(bid -> {
                                                    existing.setBrokerAccountId(bid);
                                                    return subs.save(existing);
                                                })
                                                .switchIfEmpty(subs.save(existing))
                                                .map(s -> Map.<String, Object>of(
                                                        "childId", childId.toString(), "status", "REACTIVATED", "subscriptionId", s.getId()));
                                    }
                                    return subs.save(existing).map(s -> Map.<String, Object>of(
                                            "childId", childId.toString(), "status", "REACTIVATED", "subscriptionId", s.getId()));
                                }
                                return Mono.just(Map.<String, Object>of("childId", childId.toString(), "status", "ALREADY_LINKED"));
                            })
                            .switchIfEmpty(Mono.defer(() ->
                                resolveChildBrokerAccount(childId)
                                        .flatMap(brokerAccountId -> {
                                            Subscription s = new Subscription();
                                            s.setMasterId(masterId);
                                            s.setChildId(childId);
                                            s.setBrokerAccountId(brokerAccountId);
                                            s.setScalingFactor(factor);
                                            s.setCopyingStatus("ACTIVE");
                                            s.setApprovedOnce(true);
                                            s.setCreatedAt(Instant.now());
                                            return subs.save(s).map(saved -> Map.<String, Object>of(
                                                    "childId", childId.toString(), "status", "LINKED", "subscriptionId", saved.getId()));
                                        })
                                        .switchIfEmpty(Mono.defer(() -> {
                                            Subscription s = new Subscription();
                                            s.setMasterId(masterId);
                                            s.setChildId(childId);
                                            s.setScalingFactor(factor);
                                            s.setCopyingStatus("ACTIVE");
                                            s.setApprovedOnce(true);
                                            s.setCreatedAt(Instant.now());
                                            return subs.save(s).map(saved -> Map.<String, Object>of(
                                                    "childId", childId.toString(), "status", "LINKED_NO_BROKER", "subscriptionId", saved.getId()));
                                        }))
                            ));
                })
                .collectList()
                .map(results -> Map.<String, Object>of("results", results));
    }

    // 4.2c Master subscribes to a child (master follows child's trades)
    public Mono<Map<String, Object>> subscribeToChild(UUID masterId, UUID childId, Double scalingFactor) {
        // Here the master becomes the follower: masterId in child_id column, childId in master_id column
        // This means the "child" user is the trade source, and the "master" user copies their trades
        return subs.findByMasterIdAndChildId(childId, masterId)
                .flatMap(e -> Mono.<Map<String, Object>>error(
                        new ResponseStatusException(HttpStatus.CONFLICT, "Already subscribed to this child")))
                .switchIfEmpty(Mono.defer(() -> {
                    Subscription s = new Subscription();
                    s.setMasterId(childId);   // child is the trade source
                    s.setChildId(masterId);   // master is the follower
                    s.setScalingFactor(scalingFactor != null ? scalingFactor : 1.0);
                    s.setCopyingStatus("ACTIVE");
                    s.setCreatedAt(Instant.now());
                    return subs.save(s).map(saved -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("subscriptionId", saved.getId());
                        r.put("message", "Subscribed to child successfully");
                        return r;
                    });
                }));
    }

    // 4.3 Unlink child (set INACTIVE, keep record for re-subscribe auto-approval)
    public Mono<Map<String, String>> unlinkChild(UUID masterId, UUID childId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found")))
                .flatMap(s -> {
                    s.setCopyingStatus("INACTIVE");
                    return subs.save(s).thenReturn(Map.of(
                            "message", "Child removed",
                            "childId", childId.toString(),
                            "status", "INACTIVE"));
                });
    }

    // 4.3b Bulk unlink children
    public Mono<Map<String, Object>> bulkUnlinkChildren(UUID masterId, List<UUID> childIds) {
        return reactor.core.publisher.Flux.fromIterable(childIds)
                .flatMap(childId -> subs.findByMasterIdAndChildId(masterId, childId)
                        .flatMap(s -> {
                            s.setCopyingStatus("INACTIVE");
                            return subs.save(s).thenReturn(Map.<String, Object>of("childId", childId.toString(), "status", "UNLINKED"));
                        })
                        .switchIfEmpty(Mono.just(Map.<String, Object>of("childId", childId.toString(), "status", "NOT_FOUND"))))
                .collectList()
                .map(results -> Map.<String, Object>of("results", results));
    }

    // 4.3c Master pauses a child's copying
    public Mono<Map<String, String>> pauseChild(UUID masterId, UUID childId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found")))
                .flatMap(s -> {
                    if (!"ACTIVE".equals(s.getCopyingStatus())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Can only pause ACTIVE subscriptions"));
                    }
                    s.setCopyingStatus("PAUSED");
                    return subs.save(s).thenReturn(Map.of("message", "Child copying paused"));
                });
    }

    // 4.3d Master resumes a child's copying
    public Mono<Map<String, String>> resumeChild(UUID masterId, UUID childId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found")))
                .flatMap(s -> {
                    if (!"PAUSED".equals(s.getCopyingStatus())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Can only resume PAUSED subscriptions"));
                    }
                    s.setCopyingStatus("ACTIVE");
                    return subs.save(s).thenReturn(Map.of("message", "Child copying resumed"));
                });
    }

    // 4.4 Get scaling
    public Mono<Map<String, Object>> getScaling(UUID masterId, UUID childId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found")))
                .map(s -> Map.<String, Object>of("childId", childId, "scalingFactor", s.getScalingFactor()));
    }

    // 4.5 Update scaling
    public Mono<Map<String, Object>> updateScaling(UUID masterId, UUID childId, double factor) {
        if (factor < 0.01 || factor > 10.0)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scaling factor must be 0.01 to 10.0"));
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found")))
                .flatMap(s -> { s.setScalingFactor(factor); return subs.save(s); })
                .map(s -> Map.<String, Object>of("childId", childId, "scalingFactor", s.getScalingFactor()));
    }

    // 4.6 Master analytics
    public Mono<Map<String, Object>> getAnalytics(UUID masterId) {
        return Mono.zip(
                logs.findByMasterId(masterId).collectList(),
                subs.findByMasterId(masterId).collectList(),
                copyLogs.findByMasterId(masterId).collectList(),
                buildPnlSnapshot(masterId)
        ).map(t -> {
            var tradeLogs = t.getT1();
            var children = t.getT2();
            var allCopyLogs = t.getT3();
            MasterPnlCalculator.PnlSnapshot snap = t.getT4();
            long totalTrades = allCopyLogs.stream().filter(l -> "SUCCESS".equals(l.getChildStatus())).count();
            long totalFailed = allCopyLogs.stream().filter(l -> "FAILED".equals(l.getChildStatus())).count();
            long totalReplications = tradeLogs.stream().filter(l -> "REPLICATED".equals(l.getType())).count();
            long activeChildren = children.stream().filter(s -> "ACTIVE".equals(s.getCopyingStatus())).count();
            long winRate = totalTrades + totalFailed > 0
                    ? Math.round(totalTrades * 100.0 / (totalTrades + totalFailed)) : 0;
            double totalPnl = snap.totalRealised() + snap.totalUnrealised();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("totalPnl", MasterChildMetricsHelper.round2(totalPnl));
            r.put("totalPnL", MasterChildMetricsHelper.round2(totalPnl));
            r.put("winRate", winRate);
            r.put("totalTrades", totalTrades);
            r.put("totalReplications", totalReplications);
            r.put("todayTradesCopied", countTodaySuccess(allCopyLogs));
            r.put("totalChildren", children.size());
            r.put("totalFollowers", children.size());
            r.put("revenue", 0);
            r.put("totalEarnings", 0);
            r.put("subscriptionRevenue", 0);
            r.put("performanceBonus", 0);
            r.put("portfolioValue", snap.portfolioValue());
            r.put("earningsBreakdown", List.of(
                    Map.of("name", "Subscription Fees", "value", 0),
                    Map.of("name", "Performance Bonus", "value", 0)
            ));
            r.put("performanceChart", snap.dailyPnl());
            r.put("pnl", snap.monthlyPnl());
            r.put("pnlSummary", snap.monthlyPnl());
            r.put("childPerformance", children.stream().map(s -> {
                long childTradesCopied = allCopyLogs.stream()
                        .filter(l -> s.getChildId().equals(l.getChildId()) && "SUCCESS".equals(l.getChildStatus()))
                        .count();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("childId", s.getChildId());
                m.put("scalingFactor", s.getScalingFactor());
                m.put("copyingStatus", s.getCopyingStatus());
                m.put("pnl", 0);
                m.put("tradesCopied", childTradesCopied);
                return m;
            }).toList());
            return r;
        });
    }

    // 4.7 Trade history (copy_logs + trades table for comprehensive history)
    public Mono<Map<String, Object>> getTradeHistory(UUID masterId) {
        return Mono.zip(
                getCopyLogs(masterId),
                tradeRepository.findByUserIdOrderByPlacedAtDesc(masterId).collectList()
        ).map(t -> {
            Map<String, Object> copyLogResult = t.getT1();
            List<com.copytrading.trade.Trade> masterTrades = t.getT2();
            Object logs = copyLogResult.get("logs");
            // Build master's own trade list
            List<Map<String, Object>> tradesList = masterTrades.stream().map(tr -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", tr.getId());
                m.put("symbol", tr.getInstrument());
                m.put("side", tr.getTransactionType());
                m.put("tradeType", tr.getTransactionType());
                m.put("qty", tr.getQuantity());
                m.put("price", tr.getPrice());
                m.put("orderType", tr.getOrderType());
                m.put("product", tr.getProduct());
                m.put("status", tr.getStatus());
                m.put("brokerOrderId", tr.getBrokerOrderId());
                m.put("exchange", tr.getExchange());
                m.put("segment", tr.getSegment());
                m.put("placedAt", tr.getPlacedAt() != null ? tr.getPlacedAt().toString() : null);
                m.put("executedAt", tr.getExecutedAt() != null ? tr.getExecutedAt().toString() : null);
                m.put("replicationsTriggered", tr.getReplicationsTriggered());
                return m;
            }).toList();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("logs", logs);
            r.put("trades", tradesList.isEmpty() ? logs : tradesList);
            r.put("tradeLogs", logs);
            r.put("copyLogs", logs);
            r.put("data", logs);
            r.put("masterTrades", tradesList);
            r.put("total", copyLogResult.getOrDefault("total", logs instanceof List<?> l ? l.size() : 0));
            r.put("totalMasterTrades", tradesList.size());
            return r;
        });
    }

    /** Square off a position on the master's active broker account. */
    public Mono<Map<String, Object>> squareOffPosition(UUID masterId, Map<String, Object> body) {
        return activeAccountRepo.findById(masterId)
                .flatMap(aa -> brokerService.closePosition(aa.getBrokerAccountId(), masterId, body))
                .switchIfEmpty(Mono.defer(() ->
                    // Fallback: try the first active broker account if no active account is set
                    brokerRepo.findByUserId(masterId)
                            .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                            .next()
                            .flatMap(a -> brokerService.closePosition(a.getId(), masterId, body))
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "No active master broker account. Set active account first.")))
                ));
    }

    // 4.8 Master dashboard (aggregated view + enriched followers)
    public Mono<Map<String, Object>> getDashboard(UUID masterId) {
        Mono<List<CopyLog>> logsMono = copyLogs.findByMasterId(masterId).collectList();
        Mono<List<Subscription>> activeMono = subs.findByMasterIdAndCopyingStatus(masterId, "ACTIVE")
                .filter(s -> !s.getChildId().equals(masterId))
                .collectList();
        Mono<List<Subscription>> allMono = subs.findByMasterId(masterId)
                .filter(s -> !s.getChildId().equals(masterId))
                .collectList();
        Mono<List<Map<String, Object>>> enrichedMono = subs.findByMasterIdAndCopyingStatus(masterId, "ACTIVE")
                .filter(s -> !s.getChildId().equals(masterId))
                .flatMap(this::buildFollowerRow, 2)
                .collectList();

        return Mono.zip(activeMono, logsMono, allMono, enrichedMono, buildPnlSnapshot(masterId)).map(t -> {
            var activeChildren = t.getT1();
            var allCopyLogs = t.getT2();
            var allSubs = t.getT3();
            var enrichedChildren = t.getT4();
            MasterPnlCalculator.PnlSnapshot snap = t.getT5();

            long totalTradesCopied = allCopyLogs.stream().filter(l -> "SUCCESS".equals(l.getChildStatus())).count();
            long totalFailed = allCopyLogs.stream().filter(l -> "FAILED".equals(l.getChildStatus())).count();
            long todayTrades = countTodaySuccess(allCopyLogs);

            Map<String, Object> r = new LinkedHashMap<>();
            long winRate = totalTradesCopied + totalFailed > 0
                    ? Math.round((totalTradesCopied * 100.0) / (totalTradesCopied + totalFailed)) : 0;
            double totalPnl = snap.totalRealised() + snap.totalUnrealised();
            r.put("activeChildren", activeChildren.size());
            r.put("totalChildren", allSubs.size());
            r.put("totalFollowers", allSubs.size());
            r.put("totalTradesCopied", totalTradesCopied);
            r.put("totalTrades", totalTradesCopied);
            r.put("totalReplications", todayTrades);
            r.put("totalFailed", totalFailed);
            r.put("todayTradesCopied", todayTrades);
            r.put("successRate", winRate);
            r.put("winRate", winRate);
            r.put("totalPnl", MasterChildMetricsHelper.round2(totalPnl));
            r.put("totalPnL", MasterChildMetricsHelper.round2(totalPnl));
            r.put("portfolioValue", snap.portfolioValue());
            r.put("pnl", snap.monthlyPnl());
            r.put("childPerformance", enrichedChildren.stream().map(c -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("childId", c.get("childId"));
                p.put("name", c.get("name"));
                p.put("scalingFactor", c.get("scalingFactor"));
                p.put("copyingStatus", c.get("copyingStatus"));
                p.put("pnl", c.getOrDefault("pnlToday", 0));
                p.put("pnlToday", c.getOrDefault("pnlToday", 0));
                p.put("tradesCopied", c.getOrDefault("tradesCopied", 0));
                return p;
            }).toList());
            r.put("children", enrichedChildren);
            r.put("summary", Map.of(
                    "totalRealisedPnl", MasterChildMetricsHelper.round2(snap.totalRealised()),
                    "totalUnrealisedPnl", MasterChildMetricsHelper.round2(snap.totalUnrealised()),
                    "combinedPnl", MasterChildMetricsHelper.round2(totalPnl),
                    "todayTradesCopied", todayTrades,
                    "successRate", winRate
            ));
            return r;
        });
    }

    /** Spec §2.4 — master trade P&L summary (live positions + copy logs). */
    public Mono<Map<String, Object>> getTradePnlSummary(UUID masterId) {
        return Mono.zip(
                logs.findByMasterId(masterId).collectList(),
                positionsService.getMasterPositions(masterId).onErrorReturn(Map.of("totalPnl", 0, "positions", List.of())),
                copyLogs.findByMasterId(masterId).collectList(),
                buildPnlSnapshot(masterId)
        ).map(t -> {
            var tradeLogs = t.getT1();
            Map<String, Object> pos = t.getT2();
            var copyLogList = t.getT3();
            MasterPnlCalculator.PnlSnapshot snap = t.getT4();
            long success = copyLogList.stream().filter(l -> "SUCCESS".equals(l.getChildStatus())).count();
            long failed = copyLogList.stream().filter(l -> "FAILED".equals(l.getChildStatus())).count();
            long winRate = success + failed > 0 ? Math.round(success * 100.0 / (success + failed)) : 0;

            Map<String, Object> summary = new LinkedHashMap<>();
            MasterPnlCalculator.applySummaryAliases(summary, snap);
            summary.put("totalTrades", tradeLogs.size());
            summary.put("copiesSuccess", success);
            summary.put("winRate", winRate);
            summary.put("avgWin", 0);
            summary.put("avgLoss", 0);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("summary", summary);
            r.put("trades", tradeLogs);
            r.put("positions", pos.getOrDefault("positions", List.of()));
            r.put("monthlyPnl", snap.monthlyPnl());
            return r;
        });
    }
}
