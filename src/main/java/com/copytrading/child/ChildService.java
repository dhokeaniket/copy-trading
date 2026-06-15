package com.copytrading.child;

import com.copytrading.auth.UserAccountRepository;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.logs.CopyLogRepository;
import com.copytrading.logs.TradeLogRepository;
import com.copytrading.engine.EngineHistoryService;
import com.copytrading.engine.SubscriptionCache;
import com.copytrading.positions.PositionDto;
import com.copytrading.positions.PositionsService;
import com.copytrading.subscription.CopySides;
import com.copytrading.subscription.Subscription;
import com.copytrading.subscription.SubscriptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChildService {

    private final SubscriptionRepository subs;
    private final UserAccountRepository users;
    private final TradeLogRepository logs;
    private final CopyLogRepository copyLogs;
    private final BrokerAccountRepository brokerRepo;
    private final PositionsService positionsService;
    private final EngineHistoryService engineHistory;
    private final SubscriptionCache subscriptionCache;

    public ChildService(SubscriptionRepository subs, UserAccountRepository users,
                        TradeLogRepository logs, CopyLogRepository copyLogs,
                        BrokerAccountRepository brokerRepo, PositionsService positionsService,
                        EngineHistoryService engineHistory, SubscriptionCache subscriptionCache) {
        this.subs = subs;
        this.users = users;
        this.logs = logs;
        this.copyLogs = copyLogs;
        this.brokerRepo = brokerRepo;
        this.positionsService = positionsService;
        this.engineHistory = engineHistory;
        this.subscriptionCache = subscriptionCache;
    }

    public Mono<Map<String, Object>> getTradeTimeline(UUID childId) {
        return engineHistory.getChildTimeline(childId)
                .map(trades -> Map.<String, Object>of("trades", trades));
    }

    // 5.1 List available masters (enriched from copy_logs + child's subscription state)
    public Mono<Map<String, Object>> listMasters(UUID childId) {
        return users.findByRole("MASTER")
                .flatMap(m -> Mono.zip(
                        subs.findByMasterIdAndChildId(m.getId(), childId)
                                .map(Optional::of)
                                .defaultIfEmpty(Optional.empty()),
                        copyLogs.findByMasterId(m.getId()).collectList()
                                .onErrorReturn(List.of()),
                        subs.findByMasterIdAndCopyingStatus(m.getId(), "ACTIVE").count()
                                .onErrorReturn(0L)
                ).map(t -> buildMasterDiscoveryCard(m, t.getT1(), t.getT2(), t.getT3()))
                .onErrorResume(e -> Mono.empty()))
                .collectList()
                .map(list -> Map.<String, Object>of("masters", list));
    }

    private static Map<String, Object> buildMasterDiscoveryCard(
            com.copytrading.auth.UserAccount m,
            Optional<Subscription> mySub,
            List<com.copytrading.logs.CopyLog> masterLogs,
            long activeSubscribers) {
        long success = masterLogs.stream().filter(l -> "SUCCESS".equals(l.getChildStatus())).count();
        long failed = masterLogs.stream().filter(l -> "FAILED".equals(l.getChildStatus())).count();
        long total = success + failed;
        double winRate = total > 0 ? Math.round(success * 100.0 / total) : 0;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("masterId", m.getId());
        r.put("id", m.getId());
        r.put("name", m.getName());
        r.put("masterName", m.getName());
        r.put("email", m.getEmail());
        r.put("winRate", winRate);
        r.put("totalTrades", success);
        r.put("totalCopies", masterLogs.size());
        r.put("failedCopies", failed);
        r.put("avgPnl", 0);
        r.put("subscribers", activeSubscribers);
        r.put("return30d", winRate);
        r.put("returnYTD", winRate);
        r.put("riskLevel", total > 50 ? "Medium" : "Low");
        r.put("bestTrade", "—");
        r.put("worstTrade", "—");
        r.put("verified", true);
        r.put("description", m.getName() + " — Master trader on Ascentra");
        r.put("markets", List.of("Equity", "F&O"));
        r.put("equityCurve", List.of(100, 100, 100, 100, 100, 100));
        mySub.ifPresent(s -> {
            r.put("mySubscriptionStatus", s.getCopyingStatus());
            r.put("myScalingFactor", s.getScalingFactor());
            r.put("subscribed", "ACTIVE".equals(s.getCopyingStatus()) || "PAUSED".equals(s.getCopyingStatus()));
        });
        if (!r.containsKey("mySubscriptionStatus")) {
            r.put("mySubscriptionStatus", "NOT_SUBSCRIBED");
            r.put("subscribed", false);
        }
        return r;
    }

    // 5.2 Subscribe to master
    // New child → PENDING_APPROVAL (master must approve)
    // Previously approved child (unsubscribed & re-subscribing) → ACTIVE directly
    public Mono<Map<String, Object>> subscribe(UUID childId, UUID masterId, UUID brokerAccountId, Double scalingFactor) {
        return subscribe(childId, masterId, brokerAccountId, scalingFactor, null, null);
    }

    public Mono<Map<String, Object>> subscribe(UUID childId, UUID masterId, UUID brokerAccountId, Double scalingFactor,
                                             String copySides, Boolean allowShortSelling) {
        if (brokerAccountId == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "brokerAccountId is required. Please link a broker account before subscribing."));
        }
        double factor = (scalingFactor != null && scalingFactor >= 0.01 && scalingFactor <= 10.0) ? scalingFactor : 1.0;
        return brokerRepo.findById(brokerAccountId)
                .filter(a -> a.getUserId().equals(childId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "This broker account does not belong to you")))
                .flatMap(account -> subs.findByMasterIdAndChildId(masterId, childId)
                .flatMap(existing -> {
                    // If already exists and active/paused → conflict
                    if ("ACTIVE".equals(existing.getCopyingStatus()) || "PAUSED".equals(existing.getCopyingStatus())
                            || "PENDING_APPROVAL".equals(existing.getCopyingStatus())) {
                        return Mono.<Map<String, Object>>error(
                                new ResponseStatusException(HttpStatus.CONFLICT, "Already subscribed or pending approval"));
                    }
                    // If previously approved (INACTIVE/REJECTED but approvedOnce=true) → reactivate directly
                    if (existing.isApprovedOnce()) {
                        existing.setCopyingStatus("ACTIVE");
                        existing.setBrokerAccountId(brokerAccountId);
                        existing.setCreatedAt(Instant.now());
                        applyCopyPreferences(existing, copySides, allowShortSelling);
                        return subs.save(existing).map(saved -> {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("subscriptionId", saved.getId());
                            r.put("status", "ACTIVE");
                            r.put("message", "Re-subscribed successfully (previously approved)");
                            return r;
                        });
                    }
                    // Never approved before → set to pending
                    existing.setCopyingStatus("PENDING_APPROVAL");
                    existing.setBrokerAccountId(brokerAccountId);
                    existing.setCreatedAt(Instant.now());
                    applyCopyPreferences(existing, copySides, allowShortSelling);
                    return subs.save(existing).map(saved -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("subscriptionId", saved.getId());
                        r.put("status", "PENDING_APPROVAL");
                        r.put("message", "Subscription request sent. Waiting for master approval.");
                        return r;
                    });
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // Brand new subscription → PENDING_APPROVAL
                    Subscription s = new Subscription();
                    s.setMasterId(masterId);
                    s.setChildId(childId);
                    s.setBrokerAccountId(brokerAccountId);
                    s.setScalingFactor(factor);
                    s.setCopyingStatus("PENDING_APPROVAL");
                    s.setApprovedOnce(false);
                    s.setCreatedAt(Instant.now());
                    applyCopyPreferences(s, copySides, allowShortSelling);
                    return subs.save(s).map(saved -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("subscriptionId", saved.getId());
                        r.put("status", "PENDING_APPROVAL");
                        r.put("message", "Subscription request sent. Waiting for master approval.");
                        return r;
                    });
                })));
    }

    private static void applyCopyPreferences(Subscription s, String copySides, Boolean allowShortSelling) {
        if (copySides != null && !copySides.isBlank()) {
            s.setCopySides(CopySides.normalize(copySides));
        } else if (s.getCopySides() == null) {
            s.setCopySides(CopySides.BUY_ONLY);
        }
        if (allowShortSelling != null) {
            s.setAllowShortSelling(allowShortSelling);
        }
    }

    // 5.2b Bulk subscribe to multiple masters (with approval logic)
    public Mono<Map<String, Object>> bulkSubscribe(UUID childId, List<Map<String, Object>> masters) {
        return reactor.core.publisher.Flux.fromIterable(masters)
                .flatMap(m -> {
                    UUID masterId = UUID.fromString(m.get("masterId").toString());
                    UUID brokerAccountId = m.containsKey("brokerAccountId") && m.get("brokerAccountId") != null
                            ? UUID.fromString(m.get("brokerAccountId").toString()) : null;
                    double factor = m.containsKey("scalingFactor") && m.get("scalingFactor") != null
                            ? ((Number) m.get("scalingFactor")).doubleValue() : 1.0;
                    return subs.findByMasterIdAndChildId(masterId, childId)
                            .flatMap(existing -> {
                                if ("ACTIVE".equals(existing.getCopyingStatus()) || "PAUSED".equals(existing.getCopyingStatus())
                                        || "PENDING_APPROVAL".equals(existing.getCopyingStatus())) {
                                    return Mono.just(Map.<String, Object>of("masterId", masterId.toString(), "status", "ALREADY_SUBSCRIBED"));
                                }
                                // INACTIVE/REJECTED — re-subscribe
                                if (existing.isApprovedOnce()) {
                                    existing.setCopyingStatus("ACTIVE");
                                    existing.setBrokerAccountId(brokerAccountId);
                                    return subs.save(existing).map(s -> Map.<String, Object>of(
                                            "masterId", masterId.toString(), "status", "RE_SUBSCRIBED", "subscriptionId", s.getId()));
                                }
                                existing.setCopyingStatus("PENDING_APPROVAL");
                                existing.setBrokerAccountId(brokerAccountId);
                                return subs.save(existing).map(s -> Map.<String, Object>of(
                                        "masterId", masterId.toString(), "status", "PENDING_APPROVAL", "subscriptionId", s.getId()));
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                Subscription s = new Subscription();
                                s.setMasterId(masterId);
                                s.setChildId(childId);
                                s.setBrokerAccountId(brokerAccountId);
                                s.setScalingFactor(factor);
                                s.setCopyingStatus("PENDING_APPROVAL");
                                s.setApprovedOnce(false);
                                s.setCreatedAt(Instant.now());
                                return subs.save(s).map(saved -> Map.<String, Object>of(
                                        "masterId", masterId.toString(), "status", "PENDING_APPROVAL", "subscriptionId", saved.getId()));
                            }));
                })
                .collectList()
                .map(results -> Map.<String, Object>of("results", results));
    }

    // 5.3 Unsubscribe (set INACTIVE, keep record for re-subscribe auto-approval)
    public Mono<Map<String, String>> unsubscribe(UUID childId, UUID masterId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                .flatMap(s -> {
                    s.setCopyingStatus("INACTIVE");
                    return subs.save(s).thenReturn(Map.of("message", "Unsubscribed"));
                });
    }

    // 5.3b Bulk unsubscribe from multiple masters
    public Mono<Map<String, Object>> bulkUnsubscribe(UUID childId, List<UUID> masterIds) {
        return reactor.core.publisher.Flux.fromIterable(masterIds)
                .flatMap(masterId -> subs.findByMasterIdAndChildId(masterId, childId)
                        .flatMap(s -> {
                            s.setCopyingStatus("INACTIVE");
                            return subs.save(s).thenReturn(Map.<String, Object>of("masterId", masterId.toString(), "status", "UNSUBSCRIBED"));
                        })
                        .switchIfEmpty(Mono.just(Map.<String, Object>of("masterId", masterId.toString(), "status", "NOT_FOUND"))))
                .collectList()
                .map(results -> Map.<String, Object>of("results", results));
    }

    // 5.4 List subscriptions (exclude INACTIVE)
    public Mono<Map<String, Object>> listSubscriptions(UUID childId) {
        Instant todayStart = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
                
        Mono<List<PositionDto>> positionsMono = positionsService.getPositionsForUser(childId)
                .onErrorResume(e -> Mono.just(List.of()));
        Mono<List<com.copytrading.logs.CopyLog>> copyLogsMono = copyLogs.findByChildId(childId).collectList()
                .onErrorResume(e -> Mono.just(List.of()));
                
        return Mono.zip(subs.findByChildIdAndCopyingStatusNot(childId, "INACTIVE").collectList(), copyLogsMono, positionsMono)
                .flatMap(tuple -> {
                    var subsList = tuple.getT1();
                    var childLogs = tuple.getT2();
                    var livePositions = tuple.getT3();
                    
                    return reactor.core.publisher.Flux.fromIterable(subsList)
                            .flatMap(s -> users.findById(s.getMasterId())
                                    .map(m -> {
                                        long copied = childLogs.stream()
                                                .filter(l -> s.getMasterId().equals(l.getMasterId())
                                                        && "SUCCESS".equals(l.getChildStatus()))
                                                .count();
                                        long todayCopied = childLogs.stream()
                                                .filter(l -> s.getMasterId().equals(l.getMasterId())
                                                        && "SUCCESS".equals(l.getChildStatus())
                                                        && l.getCreatedAt() != null
                                                        && !l.getCreatedAt().isBefore(todayStart))
                                                .count();
                                        
                                        double[] metrics = calculateMasterMetrics(s.getMasterId(), childLogs, livePositions);
                                        
                                        Map<String, Object> r = new LinkedHashMap<>();
                                        r.put("subscriptionId", s.getId());
                                        r.put("masterId", s.getMasterId());
                                        r.put("masterName", m.getName());
                                        r.put("scalingFactor", s.getScalingFactor());
                                        r.put("copySides", CopySides.normalize(s.getCopySides()));
                                        r.put("allowShortSelling", s.isAllowShortSelling());
                                        r.put("priceTolerancePct", s.getPriceTolerancePct());
                                        r.put("copyingStatus", s.getCopyingStatus());
                                        r.put("subscribedAt", s.getCreatedAt());
                                        r.put("brokerAccountId", s.getBrokerAccountId());
                                        r.put("pnl", metrics[0]);
                                        r.put("totalPnL", metrics[0]);
                                        r.put("tradesCopied", copied);
                                        r.put("tradesCopiedToday", todayCopied);
                                        r.put("allocation", metrics[1]);
                                        r.put("allocationAmount", metrics[1]);
                                        return r;
                                    }))
                            .collectList();
                })
                .map(list -> Map.<String, Object>of("subscriptions", list));
    }

    // 5.5 Get scaling
    public Mono<Map<String, Object>> getScaling(UUID childId, UUID masterId) {
        if (masterId != null) {
            return subs.findByMasterIdAndChildId(masterId, childId)
                    .map(s -> Map.<String, Object>of("scalingFactor", s.getScalingFactor()))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")));
        }
        return subs.findByChildId(childId).next()
                .map(s -> Map.<String, Object>of("scalingFactor", s.getScalingFactor()))
                .switchIfEmpty(Mono.just(Map.of("scalingFactor", 1.0)));
    }

    public Mono<Map<String, Object>> updateCopySettings(UUID childId, UUID masterId, String copySides,
                                                        Boolean allowShortSelling) {
        return updateCopySettings(childId, masterId, copySides, allowShortSelling, null);
    }

    public Mono<Map<String, Object>> updateCopySettings(UUID childId, UUID masterId, String copySides,
                                                        Boolean allowShortSelling, Double priceTolerancePct) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                .flatMap(s -> {
                    applyCopyPreferences(s, copySides, allowShortSelling);
                    if (priceTolerancePct != null && priceTolerancePct >= 0 && priceTolerancePct <= 10.0) {
                        s.setPriceTolerancePct(priceTolerancePct);
                    }
                    return subs.save(s);
                })
                .doOnNext(s -> subscriptionCache.invalidate(masterId))
                .map(s -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("masterId", masterId);
                    r.put("copySides", CopySides.normalize(s.getCopySides()));
                    r.put("allowShortSelling", s.isAllowShortSelling());
                    r.put("priceTolerancePct", s.getPriceTolerancePct());
                    r.put("message", "Copy settings updated");
                    return r;
                });
    }

    // 5.6 Update scaling
    public Mono<Map<String, Object>> updateScaling(UUID childId, UUID masterId, double factor) {
        if (factor < 0.01 || factor > 10.0)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scaling factor must be 0.01 to 10.0"));
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                .flatMap(s -> { s.setScalingFactor(factor); return subs.save(s); })
                .map(s -> Map.<String, Object>of("scalingFactor", s.getScalingFactor()));
    }

    // 5.7 Pause copying
    public Mono<Map<String, String>> pauseCopying(UUID childId, UUID masterId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                .flatMap(s -> { s.setCopyingStatus("PAUSED"); return subs.save(s); })
                .thenReturn(Map.of("message", "Copying paused"));
    }

    // 5.8 Resume copying
    public Mono<Map<String, String>> resumeCopying(UUID childId, UUID masterId) {
        return subs.findByMasterIdAndChildId(masterId, childId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                .flatMap(s -> { s.setCopyingStatus("ACTIVE"); return subs.save(s); })
                .thenReturn(Map.of("message", "Copying resumed"));
    }

    // 5.9 Copied trades (enriched with live P&L from broker positions)
    public Mono<Map<String, Object>> getCopiedTrades(UUID childId) {
        // Fetch live positions in parallel with copy logs
        Mono<List<PositionDto>> positionsMono = positionsService.getPositionsForUser(childId)
                .onErrorResume(e -> Mono.just(List.of()));

        return Mono.zip(copyLogs.findByChildId(childId).collectList(), positionsMono).flatMap(tuple -> {
            var logsList = tuple.getT1();
            var livePositions = tuple.getT2();

            // Build a lookup map: symbol -> PositionDto (for matching)
            Map<String, PositionDto> positionMap = livePositions.stream()
                    .collect(Collectors.toMap(
                            p -> p.getSymbol().toUpperCase(),
                            p -> p,
                            (a, b) -> a // keep first if duplicate
                    ));

            // Get master names for each log
            Set<UUID> masterIds = logsList.stream().map(l -> l.getMasterId()).filter(Objects::nonNull).collect(Collectors.toSet());
            return reactor.core.publisher.Flux.fromIterable(masterIds)
                    .flatMap(mid -> users.findById(mid).map(u -> Map.entry(mid, u.getName())))
                    .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                    .map(masterNames -> {
                        var trades = logsList.stream().map(l -> {
                            Map<String, Object> t = new LinkedHashMap<>();
                            t.put("id", l.getId());
                            t.put("copyGroupId", l.getCopyGroupId());
                            t.put("masterId", l.getMasterId() != null ? l.getMasterId().toString() : null);
                            t.put("masterName", masterNames.getOrDefault(l.getMasterId(), "Unknown"));
                            t.put("instrument", l.getSymbol());
                            t.put("type", l.getTradeType());
                            t.put("product", l.getProduct());
                            t.put("orderType", l.getOrderType());
                            t.put("price", l.getPrice());
                            t.put("triggerPrice", l.getTriggerPrice());
                            t.put("masterQty", l.getQty() != null ? l.getQty() : 0);
                            t.put("myQty", l.getQty() != null ? l.getQty() : 0);
                            t.put("status", l.getChildStatus());
                            t.put("skipReason", l.getSkipReason());
                            t.put("errorMessage", l.getErrorMessage());
                            t.put("failureReason", l.getErrorMessage() != null ? l.getErrorMessage() : l.getSkipReason());
                            t.put("latencyMs", l.getLatencyMs());
                            t.put("engineReceivedAt", l.getEngineReceivedAt() != null ? l.getEngineReceivedAt().toString() : null);
                            t.put("childPlacedAt", l.getChildPlacedAt() != null ? l.getChildPlacedAt().toString() : null);
                            t.put("time", l.getCreatedAt() != null ? l.getCreatedAt().toString() : null);

                            // Enrich with live position data if symbol matches
                            String symbol = l.getSymbol() != null ? l.getSymbol().toUpperCase() : "";
                            PositionDto pos = positionMap.get(symbol);
                            if (pos == null && symbol.contains("-")) {
                                // Try without suffix (e.g., RELIANCE-EQ -> RELIANCE)
                                pos = positionMap.get(symbol.split("-")[0]);
                            }

                            if (pos != null) {
                                t.put("entry", pos.getAvgPrice());
                                t.put("current", pos.getLtp());
                                t.put("ltp", pos.getLtp());
                                t.put("pnl", pos.getPnl());
                            } else {
                                t.put("entry", 0);
                                t.put("current", 0);
                                t.put("ltp", 0);
                                t.put("pnl", 0);
                            }
                            return t;
                        }).toList();
                        return Map.<String, Object>of("trades", trades);
                    });
        });
    }

    // Copy logs scoped to child
    public Mono<Map<String, Object>> getCopyLogs(UUID childId) {
        return copyLogs.findByChildId(childId).collectList().flatMap(logsList -> {
            Set<UUID> masterIds = logsList.stream().map(l -> l.getMasterId()).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            return reactor.core.publisher.Flux.fromIterable(masterIds)
                    .flatMap(mid -> users.findById(mid).map(u -> Map.entry(mid, u.getName())))
                    .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                    .map(masterNames -> {
                        var logs = logsList.stream().map(l -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", l.getId());
                            m.put("masterId", l.getMasterId() != null ? l.getMasterId().toString() : null);
                            m.put("masterName", masterNames.getOrDefault(l.getMasterId(), "Unknown"));
                            m.put("childId", l.getChildId() != null ? l.getChildId().toString() : null);
                            m.put("symbol", l.getSymbol());
                            m.put("qty", l.getQty());
                            m.put("tradeType", l.getTradeType());
                            m.put("masterStatus", l.getMasterStatus());
                            m.put("childStatus", l.getChildStatus());
                            m.put("errorMessage", l.getErrorMessage());
                            m.put("skipReason", l.getSkipReason());
                            m.put("latencyMs", l.getLatencyMs());
                            m.put("copyGroupId", l.getCopyGroupId());
                            m.put("engineReceivedAt", l.getEngineReceivedAt() != null ? l.getEngineReceivedAt().toString() : null);
                            m.put("childPlacedAt", l.getChildPlacedAt() != null ? l.getChildPlacedAt().toString() : null);
                            m.put("createdAt", l.getCreatedAt() != null ? l.getCreatedAt().toString() : null);
                            return m;
                        }).toList();
                        return Map.<String, Object>of("logs", logs);
                    });
        });
    }

    // Switch broker account for a subscription (no unsubscribe needed)
    public Mono<Map<String, Object>> switchBroker(UUID childId, UUID masterId, UUID brokerAccountId) {
        if (brokerAccountId == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "brokerAccountId is required"));
        }
        return brokerRepo.findById(brokerAccountId)
                .filter(a -> a.getUserId().equals(childId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "This broker account does not belong to you")))
                .flatMap(account -> subs.findByMasterIdAndChildId(masterId, childId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found")))
                        .flatMap(s -> {
                            s.setBrokerAccountId(brokerAccountId);
                            return subs.save(s).map(saved -> {
                                Map<String, Object> r = new java.util.LinkedHashMap<>();
                                r.put("message", "Broker account switched");
                                r.put("brokerAccountId", brokerAccountId.toString());
                                r.put("brokerId", account.getBrokerId());
                                r.put("brokerName", account.getBrokerId());
                                return r;
                            });
                        }));
    }

    // 5.10 Child analytics (copy_logs + live positions)
    public Mono<Map<String, Object>> getAnalytics(UUID childId) {
        return Mono.zip(
                copyLogs.findByChildId(childId).collectList(),
                positionsService.getChildPositions(childId).onErrorReturn(Map.of("totalPnl", 0, "positions", List.of())),
                subs.findByChildIdAndCopyingStatusNot(childId, "INACTIVE").count()
        ).map(t -> {
            var copyLogList = t.getT1();
            Map<String, Object> pos = t.getT2();
            long activeMasters = t.getT3();
            long copied = copyLogList.stream().filter(l -> "SUCCESS".equals(l.getChildStatus()) && l.getMasterId() != null).count();
            long personal = copyLogList.stream().filter(l -> "SUCCESS".equals(l.getChildStatus()) && l.getMasterId() == null).count();
            long failed = copyLogList.stream().filter(l -> "FAILED".equals(l.getChildStatus())).count();
            long skipped = copyLogList.stream().filter(l -> "SKIPPED".equals(l.getChildStatus())).count();
            long total = copied + personal + failed;
            double winRate = total > 0 ? Math.round((copied + personal) * 100.0 / total) : 0;

            List<PositionDto> livePositions = new ArrayList<>();
            Object posListObj = pos.get("positions");
            if (posListObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof PositionDto p) livePositions.add(p);
                }
            }
            double unrealized = toDouble(pos.get("totalPnl"));

            double[] metrics = calculateGlobalMetrics(copyLogList, livePositions);
            double globalTotal = metrics[0];
            double copiedPnl = metrics[1];
            double personalPnl = metrics[2];
            double realizedPnl = Math.round((globalTotal - unrealized) * 100.0) / 100.0;

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("totalPnl", globalTotal);
            r.put("totalPnL", globalTotal);
            r.put("personalPnL", personalPnl);
            r.put("copiedPnL", copiedPnl);
            r.put("masterPnL", 0);
            r.put("unrealizedPnl", unrealized);
            r.put("totalUnrealizedPnl", unrealized);
            r.put("realizedPnl", realizedPnl);
            r.put("combinedPnl", globalTotal);
            r.put("personalTrades", personal);
            r.put("copiedTrades", copied);
            r.put("skippedCopies", skipped);
            r.put("failedReplications", failed);
            r.put("portfolioValue", unrealized);
            r.put("winRate", winRate);
            r.put("activeMasters", activeMasters);
            r.put("openPositions", pos.getOrDefault("positions", List.of()));
            r.put("pnlHistory", List.of(
                    Map.of("time", java.time.LocalDate.now().minusDays(4).toString(), "personal", 0, "copied", 0),
                    Map.of("time", java.time.LocalDate.now().minusDays(3).toString(), "personal", 0, "copied", 0),
                    Map.of("time", java.time.LocalDate.now().minusDays(2).toString(), "personal", 0, "copied", 0),
                    Map.of("time", java.time.LocalDate.now().minusDays(1).toString(), "personal", 0, "copied", copied),
                    Map.of("time", java.time.LocalDate.now().toString(), "personal", personal, "copied", copied)
            ));
            r.put("personalTradesList", List.of());
            r.put("masterPnlComparison", Map.of(
                    "masterPnl", 0,
                    "childPnl", globalTotal,
                    "replicationAccuracy", winRate));
            return r;
        });
    }

    private static double toDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0; }
    }

    private double[] calculateMasterMetrics(UUID masterId, List<com.copytrading.logs.CopyLog> logs, List<PositionDto> livePositions) {
        Map<String, Double> ltpMap = livePositions.stream()
                .collect(Collectors.toMap(p -> p.getSymbol().toUpperCase(), PositionDto::getLtp, (a, b) -> a));

        Map<String, List<com.copytrading.logs.CopyLog>> bySymbol = logs.stream()
                .filter(l -> masterId.equals(l.getMasterId()) && "SUCCESS".equals(l.getChildStatus()) && l.getSymbol() != null)
                .collect(Collectors.groupingBy(l -> l.getSymbol().toUpperCase()));

        double totalMasterPnl = 0.0;
        double totalAllocation = 0.0;

        for (Map.Entry<String, List<com.copytrading.logs.CopyLog>> entry : bySymbol.entrySet()) {
            String sym = entry.getKey();
            double buyValue = 0;
            double sellValue = 0;
            int netQty = 0;
            int totalBuyQty = 0;
            double lastPriceInLog = 0;

            for (com.copytrading.logs.CopyLog l : entry.getValue()) {
                double price = l.getPrice() != null ? l.getPrice() : 0.0;
                int qty = l.getChildQty() != null ? l.getChildQty() : (l.getQty() != null ? l.getQty() : 0);
                if (price > 0) lastPriceInLog = price;

                if ("BUY".equalsIgnoreCase(l.getTradeType())) {
                    buyValue += (price * qty);
                    netQty += qty;
                    totalBuyQty += qty;
                } else if ("SELL".equalsIgnoreCase(l.getTradeType())) {
                    sellValue += (price * qty);
                    netQty -= qty;
                }
            }

            double ltp = ltpMap.getOrDefault(sym, lastPriceInLog);
            double symbolPnl = sellValue - buyValue + (netQty * ltp);
            totalMasterPnl += symbolPnl;

            if (netQty > 0 && totalBuyQty > 0) {
                double avgBuyPrice = buyValue / totalBuyQty;
                totalAllocation += (netQty * avgBuyPrice);
            } else if (netQty < 0) {
                // For short selling, allocation could be considered the margin required, but we'll approximate with the sell value
                totalAllocation += Math.abs(netQty * ltp);
            }
        }
        
        return new double[]{Math.round(totalMasterPnl * 100.0) / 100.0, Math.round(totalAllocation * 100.0) / 100.0};
    }

    private double[] calculateGlobalMetrics(List<com.copytrading.logs.CopyLog> logs, List<PositionDto> livePositions) {
        Map<String, Double> ltpMap = livePositions.stream()
                .collect(Collectors.toMap(p -> p.getSymbol().toUpperCase(), PositionDto::getLtp, (a, b) -> a));

        Map<String, List<com.copytrading.logs.CopyLog>> bySymbol = logs.stream()
                .filter(l -> "SUCCESS".equals(l.getChildStatus()) && l.getSymbol() != null)
                .collect(Collectors.groupingBy(l -> l.getSymbol().toUpperCase()));

        double globalTotal = 0.0;
        double copiedPnl = 0.0;
        double personalPnl = 0.0;

        for (Map.Entry<String, List<com.copytrading.logs.CopyLog>> entry : bySymbol.entrySet()) {
            String sym = entry.getKey();
            double buyValue = 0, sellValue = 0;
            double copiedBuy = 0, copiedSell = 0;
            double personalBuy = 0, personalSell = 0;
            int netQty = 0, copiedNet = 0, personalNet = 0;
            double lastPriceInLog = 0;

            for (com.copytrading.logs.CopyLog l : entry.getValue()) {
                double price = l.getPrice() != null ? l.getPrice() : 0.0;
                int qty = l.getChildQty() != null ? l.getChildQty() : (l.getQty() != null ? l.getQty() : 0);
                if (price > 0) lastPriceInLog = price;

                boolean isCopied = l.getMasterId() != null;

                if ("BUY".equalsIgnoreCase(l.getTradeType())) {
                    buyValue += (price * qty);
                    netQty += qty;
                    if (isCopied) { copiedBuy += (price * qty); copiedNet += qty; }
                    else { personalBuy += (price * qty); personalNet += qty; }
                } else if ("SELL".equalsIgnoreCase(l.getTradeType())) {
                    sellValue += (price * qty);
                    netQty -= qty;
                    if (isCopied) { copiedSell += (price * qty); copiedNet -= qty; }
                    else { personalSell += (price * qty); personalNet -= qty; }
                }
            }

            double ltp = ltpMap.getOrDefault(sym, lastPriceInLog);
            
            globalTotal += (sellValue - buyValue + (netQty * ltp));
            copiedPnl += (copiedSell - copiedBuy + (copiedNet * ltp));
            personalPnl += (personalSell - personalBuy + (personalNet * ltp));
        }

        return new double[]{
            Math.round(globalTotal * 100.0) / 100.0,
            Math.round(copiedPnl * 100.0) / 100.0,
            Math.round(personalPnl * 100.0) / 100.0
        };
    }
}
