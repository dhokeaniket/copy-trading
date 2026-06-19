package com.copytrading.admin;

import com.copytrading.admin.dto.*;
import com.copytrading.auth.AuthService;
import com.copytrading.auth.UserAccount;
import com.copytrading.auth.UserAccountRepository;
import com.copytrading.auth.RefreshTokenRepository;
import com.copytrading.auth.dto.UserDto;
import com.copytrading.logs.TradeLogRepository;
import com.copytrading.subscription.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AuthService authService;
    private final SubscriptionRepository subscriptionRepo;
    private final TradeLogRepository tradeLogRepo;
    private final com.copytrading.broker.BrokerAccountRepository brokerAccountRepo;
    private final com.copytrading.trade.TradeRepository tradeRepo;
    private final com.copytrading.notification.NotificationRepository notificationRepo;
    private final com.copytrading.master.MasterActiveAccountRepository masterActiveRepo;
    private final com.copytrading.logs.CopyLogRepository copyLogRepo;
    private final org.springframework.r2dbc.core.DatabaseClient databaseClient;

    public AdminService(UserAccountRepository users,
                        RefreshTokenRepository refreshTokens,
                        AuthService authService,
                        SubscriptionRepository subscriptionRepo,
                        TradeLogRepository tradeLogRepo,
                        com.copytrading.broker.BrokerAccountRepository brokerAccountRepo,
                        com.copytrading.trade.TradeRepository tradeRepo,
                        com.copytrading.notification.NotificationRepository notificationRepo,
                        com.copytrading.master.MasterActiveAccountRepository masterActiveRepo,
                        com.copytrading.logs.CopyLogRepository copyLogRepo,
                        org.springframework.r2dbc.core.DatabaseClient databaseClient) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.authService = authService;
        this.subscriptionRepo = subscriptionRepo;
        this.tradeLogRepo = tradeLogRepo;
        this.brokerAccountRepo = brokerAccountRepo;
        this.tradeRepo = tradeRepo;
        this.notificationRepo = notificationRepo;
        this.masterActiveRepo = masterActiveRepo;
        this.copyLogRepo = copyLogRepo;
        this.databaseClient = databaseClient;
    }

    // 2.1 List users with optional filters
    public Mono<Map<String, Object>> listUsers(String role, String status, int page, int limit) {
        Flux<UserAccount> flux;
        if (role != null && status != null) {
            flux = users.findByRoleAndStatus(role.toUpperCase(), status.toUpperCase());
        } else if (role != null) {
            flux = users.findByRole(role.toUpperCase());
        } else if (status != null) {
            flux = users.findByStatus(status.toUpperCase());
        } else {
            flux = users.findAll();
        }
        return flux.collectList().flatMap(all -> {
            all.sort((u1, u2) -> {
                Instant t1 = u1.getCreatedAt() != null ? u1.getCreatedAt() : Instant.EPOCH;
                Instant t2 = u2.getCreatedAt() != null ? u2.getCreatedAt() : Instant.EPOCH;
                return t2.compareTo(t1);
            });
            int total = all.size();
            int start = (page - 1) * limit;
            int end = Math.min(start + limit, total);
            List<UserDto> pageData = start < total
                    ? all.subList(start, end).stream().map(UserDto::from).toList()
                    : List.of();
            return reactor.core.publisher.Flux.fromIterable(pageData)
                    .concatMap(dto -> brokerAccountRepo.findByUserId(dto.getUserId()).collectList()
                            .map(brokers -> {
                                dto.setBrokerAccounts(new java.util.ArrayList<>(brokers));
                                return dto;
                            }))
                    .collectList()
                    .map(enrichedPageData -> {
                        Map<String, Object> resp = new LinkedHashMap<>();
                        resp.put("users", enrichedPageData);
                        resp.put("total", total);
                        resp.put("page", page);
                        return resp;
                    });
        });
    }

    // 2.2 Create Master
    public Mono<Map<String, Object>> createMaster(CreateMasterRequest req) {
        return authService.createUser(req.getName(), req.getEmail(), req.getPassword(), "MASTER", req.getPhone())
                .map(u -> {
                    log.info("ADMIN_CREATE_MASTER id={} email={}", u.getId(), u.getEmail());
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("userId", u.getId());
                    resp.put("message", "Master account created");
                    return resp;
                });
    }

    // 2.3 Create Child
    public Mono<Map<String, Object>> createChild(CreateChildRequest req) {
        return authService.createUser(req.getName(), req.getEmail(), req.getPassword(), "CHILD", req.getPhone())
                .map(u -> {
                    log.info("ADMIN_CREATE_CHILD id={} email={}", u.getId(), u.getEmail());
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("userId", u.getId());
                    resp.put("message", "Child account created");
                    return resp;
                });
    }

    // 2.3b Create Admin
    public Mono<Map<String, Object>> createAdmin(CreateAdminRequest req) {
        return authService.createUser(req.getName(), req.getEmail(), req.getPassword(), "ADMIN", req.getPhone())
                .map(u -> {
                    log.info("ADMIN_CREATE_ADMIN id={} email={}", u.getId(), u.getEmail());
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("userId", u.getId());
                    resp.put("message", "Admin account created");
                    return resp;
                });
    }

    // 2.4 Get user by ID
    public Mono<UserDto> getUserById(UUID userId) {
        return users.findById(userId)
                .map(UserDto::from)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));
    }

    // 2.5 Update user
    public Mono<UserDto> updateUser(UUID userId, UpdateUserRequest req) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    if (req.getName() != null) u.setName(req.getName().trim());
                    if (req.getEmail() != null) u.setEmail(req.getEmail().toLowerCase().trim());
                    if (req.getPhone() != null) u.setPhone(req.getPhone().trim());
                    u.setUpdatedAt(Instant.now());
                    return users.save(u).map(UserDto::from);
                });
    }

    public Mono<Map<String, Object>> updateUserStatus(UUID userId, String status) {
        if (status == null || (!status.equals("ACTIVE") && !status.equals("INACTIVE"))) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status. Must be ACTIVE or INACTIVE"));
        }
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    u.setStatus(status);
                    u.setUpdatedAt(Instant.now());
                    return users.save(u);
                })
                .map(u -> Map.of("message", "User status updated", "userId", u.getId(), "status", u.getStatus()));
    }

    // 2.6 Activate user
    public Mono<Map<String, String>> activateUser(UUID userId) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    u.setStatus("ACTIVE");
                    u.setUpdatedAt(Instant.now());
                    return users.save(u).thenReturn(Map.of("message", "User activated"));
                });
    }

    // 2.7 Deactivate user
    public Mono<Map<String, String>> deactivateUser(UUID userId) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    u.setStatus("INACTIVE");
                    u.setUpdatedAt(Instant.now());
                    return users.save(u)
                            .then(refreshTokens.revokeAllByUserId(userId))
                            .thenReturn(Map.of("message", "User deactivated"));
                });
    }

    // 2.8 Delete user (cascade: removes all related data using raw SQL)
    public Mono<Map<String, String>> deleteUser(UUID userId) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    if ("ADMIN".equals(u.getRole())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete admin accounts"));
                    }
                    log.warn("ADMIN_DELETE_USER id={} email={} role={}", u.getId(), u.getEmail(), u.getRole());
                    String uid = userId.toString();
                    // Execute cascade delete as raw SQL statements
                    return databaseClient.sql("DELETE FROM copy_logs WHERE master_id = :id OR child_id = :id").bind("id", userId).then()
                            .then(databaseClient.sql("DELETE FROM trade_logs WHERE master_id = :id").bind("id", userId).then())
                            .then(databaseClient.sql("DELETE FROM subscriptions WHERE master_id = :id OR child_id = :id").bind("id", userId).then())
                            .then(databaseClient.sql("DELETE FROM master_active_accounts WHERE master_id = :id").bind("id", userId).then())
                            .then(databaseClient.sql("DELETE FROM trades WHERE user_id = :id").bind("id", userId).then())
                            .then(databaseClient.sql("DELETE FROM notifications WHERE user_id = :id").bind("id", userId).then())
                            .then(databaseClient.sql("DELETE FROM broker_accounts WHERE user_id = :id").bind("id", userId).then())
                            .then(databaseClient.sql("DELETE FROM refresh_tokens WHERE user_id = :id").bind("id", userId).then())
                            .then(databaseClient.sql("DELETE FROM risk_rules WHERE user_id = :id").bind("id", userId).then())
                            .then(databaseClient.sql("DELETE FROM users WHERE id = :id").bind("id", userId).then())
                            .thenReturn(Map.of("message", "User and all related data permanently deleted"))
                            .onErrorResume(e -> {
                                log.error("ADMIN_DELETE_FAILED userId={} error={}", userId, e.getMessage());
                                return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Delete failed: " + e.getMessage()));
                            });
                });
    }

    // 2.9 Analytics
    public Mono<Map<String, Object>> getAnalytics() {
        return Mono.zip(
                users.countByRole("ADMIN"),
                users.countByRole("MASTER"),
                users.countByRole("CHILD"),
                subscriptionRepo.findAll().collectList(),
                tradeLogRepo.findAll().collectList(),
                copyLogRepo.findAll().collectList()
        ).map(tuple -> {
            long admins = tuple.getT1();
            long masters = tuple.getT2();
            long children = tuple.getT3();

            var subs = tuple.getT4();
            var logs = tuple.getT5();
            var copyLogs = tuple.getT6();
            
            long activeSubs = subs.stream().filter(s -> "ACTIVE".equals(s.getCopyingStatus())).count();
            long pausedChildren = subs.stream().filter(s -> "PAUSED".equals(s.getCopyingStatus())).count();

            Instant startOfDay = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();

            long totalTradesToday = copyLogs.stream()
                    .filter(l -> "SUCCESS".equalsIgnoreCase(l.getChildStatus()))
                    .filter(l -> l.getCreatedAt() != null && !l.getCreatedAt().isBefore(startOfDay))
                    .count();

            long totalReplications = copyLogs.stream().filter(l -> "SUCCESS".equalsIgnoreCase(l.getChildStatus())).count();

            long avgLatency = 0;
            if (!copyLogs.isEmpty()) {
                avgLatency = (long) copyLogs.stream()
                        .filter(c -> c.getLatencyMs() != null && c.getLatencyMs() > 0)
                        .mapToLong(com.copytrading.logs.CopyLog::getLatencyMs)
                        .average()
                        .orElse(0.0);
            }

            // --- Comprehensive Aggregations ---
            double todayPnl = 0.0;
            long openPositions = 0;
            long buyOrders = 0;
            long sellOrders = 0;
            long equityTrades = 0;
            long foTrades = 0;

            Map<UUID, Double> childPnlMap = new java.util.HashMap<>();
            java.time.format.DateTimeFormatter dateFormatter = java.time.format.DateTimeFormatter.ofPattern("dd MMM").withZone(java.time.ZoneId.of("Asia/Kolkata"));
            Map<String, Double> dailyPnlMap = new java.util.LinkedHashMap<>();
            
            java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
            for (int i = 29; i >= 0; i--) {
                dailyPnlMap.put(dateFormatter.format(today.minusDays(i)), 0.0);
            }

            Map<String, List<com.copytrading.logs.CopyLog>> bySymbol = copyLogs.stream()
                .filter(l -> "SUCCESS".equalsIgnoreCase(l.getChildStatus()) && l.getSymbol() != null)
                .collect(java.util.stream.Collectors.groupingBy(l -> l.getSymbol().toUpperCase()));

            for (Map.Entry<String, List<com.copytrading.logs.CopyLog>> entry : bySymbol.entrySet()) {
                String sym = entry.getKey();
                boolean isFo = sym.contains("FUT") || sym.endsWith("CE") || sym.endsWith("PE") || sym.length() > 10;
                double lastPriceInLog = 0.0;
                
                Map<UUID, Double> childBuyValue = new java.util.HashMap<>();
                Map<UUID, Double> childSellValue = new java.util.HashMap<>();
                Map<UUID, Integer> childNetQty = new java.util.HashMap<>();
                
                Map<String, Double> dayBuyValue = new java.util.HashMap<>();
                Map<String, Double> daySellValue = new java.util.HashMap<>();
                Map<String, Integer> dayNetQty = new java.util.HashMap<>();

                double todayBuyValue = 0, todaySellValue = 0;
                int todayNetQty = 0;

                for (com.copytrading.logs.CopyLog l : entry.getValue()) {
                    double price = l.getPrice() != null ? l.getPrice() : 0.0;
                    int qty = l.getChildQty() != null ? l.getChildQty() : (l.getQty() != null ? l.getQty() : 0);
                    if (price > 0) lastPriceInLog = price;

                    if ("BUY".equalsIgnoreCase(l.getTradeType())) buyOrders++;
                    if ("SELL".equalsIgnoreCase(l.getTradeType())) sellOrders++;
                    
                    if (isFo) foTrades++;
                    else equityTrades++;

                    UUID cId = l.getChildId();
                    String dayStr = l.getCreatedAt() != null ? dateFormatter.format(l.getCreatedAt()) : null;
                    boolean isToday = l.getCreatedAt() != null && !l.getCreatedAt().isBefore(startOfDay);

                    if ("BUY".equalsIgnoreCase(l.getTradeType())) {
                        if (cId != null) {
                            childBuyValue.merge(cId, price * qty, Double::sum);
                            childNetQty.merge(cId, qty, Integer::sum);
                        }
                        if (dayStr != null) {
                            dayBuyValue.merge(dayStr, price * qty, Double::sum);
                            dayNetQty.merge(dayStr, qty, Integer::sum);
                        }
                        if (isToday) {
                            todayBuyValue += price * qty;
                            todayNetQty += qty;
                        }
                    } else if ("SELL".equalsIgnoreCase(l.getTradeType())) {
                        if (cId != null) {
                            childSellValue.merge(cId, price * qty, Double::sum);
                            childNetQty.merge(cId, -qty, Integer::sum);
                        }
                        if (dayStr != null) {
                            daySellValue.merge(dayStr, price * qty, Double::sum);
                            dayNetQty.merge(dayStr, -qty, Integer::sum);
                        }
                        if (isToday) {
                            todaySellValue += price * qty;
                            todayNetQty -= qty;
                        }
                    }
                }

                double ltp = lastPriceInLog;
                todayPnl += (todaySellValue - todayBuyValue + (todayNetQty * ltp));

                for (Map.Entry<UUID, Integer> cEntry : childNetQty.entrySet()) {
                    if (cEntry.getValue() != 0) openPositions++;
                    
                    double cBuy = childBuyValue.getOrDefault(cEntry.getKey(), 0.0);
                    double cSell = childSellValue.getOrDefault(cEntry.getKey(), 0.0);
                    double cPnl = cSell - cBuy + (cEntry.getValue() * ltp);
                    childPnlMap.merge(cEntry.getKey(), cPnl, Double::sum);
                }

                for (String day : dayBuyValue.keySet()) {
                    double dBuy = dayBuyValue.getOrDefault(day, 0.0);
                    double dSell = daySellValue.getOrDefault(day, 0.0);
                    int dNet = dayNetQty.getOrDefault(day, 0);
                    double dPnl = dSell - dBuy + (dNet * ltp);
                    if (dailyPnlMap.containsKey(day)) {
                        dailyPnlMap.put(day, dailyPnlMap.get(day) + dPnl);
                    }
                }
            }

            List<Map<String, Object>> equityCurve = new java.util.ArrayList<>();
            double cumulativePnl = 0.0;
            for (Map.Entry<String, Double> dEntry : dailyPnlMap.entrySet()) {
                cumulativePnl += dEntry.getValue();
                Map<String, Object> point = new java.util.HashMap<>();
                point.put("label", dEntry.getKey());
                point.put("equity", Math.round(cumulativePnl * 100.0) / 100.0);
                point.put("followers", activeSubs);
                equityCurve.add(point);
            }

            long profitableChildren = 0;
            long losingChildren = 0;
            for (Double pnl : childPnlMap.values()) {
                if (pnl > 0) profitableChildren++;
                else if (pnl < 0) losingChildren++;
            }

            long totalAssetTrades = equityTrades + foTrades;
            double equityPct = totalAssetTrades > 0 ? (double) equityTrades / totalAssetTrades * 100 : 52.0;
            double foPct = totalAssetTrades > 0 ? (double) foTrades / totalAssetTrades * 100 : 48.0;

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("totalUsers", admins + masters + children);
            resp.put("totalAdmins", admins);
            resp.put("totalMasters", masters);
            resp.put("activeMasters", masters);
            resp.put("totalChildren", children);
            
            resp.put("todayPnl", Math.round(todayPnl * 100.0) / 100.0);
            resp.put("openPositions", openPositions);
            resp.put("buyOrders", buyOrders);
            resp.put("sellOrders", sellOrders);
            resp.put("equityPercentage", Math.round(equityPct));
            resp.put("foPercentage", Math.round(foPct));
            resp.put("profitableChildren", profitableChildren);
            resp.put("losingChildren", losingChildren);
            resp.put("pausedChildren", pausedChildren);
            resp.put("equityCurve", equityCurve);

            resp.put("totalTrades", totalTradesToday);
            resp.put("latency", avgLatency);
            resp.put("totalReplications", totalReplications);
            resp.put("volumeToday", totalTradesToday);
            resp.put("tradeVolume", 0);
            resp.put("activeSubscriptions", activeSubs);
            resp.put("revenueMtd", 0);
            return resp;
        });
    }

    // 2.10 System health
    public Mono<Map<String, Object>> getSystemHealth() {
        return copyLogRepo.findAll().collectList().map(copyLogs -> {
            long avgLatency = 0;
            if (!copyLogs.isEmpty()) {
                avgLatency = (long) copyLogs.stream()
                        .filter(c -> c.getLatencyMs() != null && c.getLatencyMs() > 0)
                        .mapToLong(com.copytrading.logs.CopyLog::getLatencyMs)
                        .average()
                        .orElse(0.0);
            }
            Runtime rt = Runtime.getRuntime();
            long maxMem = rt.maxMemory();
            long usedMem = rt.totalMemory() - rt.freeMemory();
            double memPct = (double) usedMem / maxMem * 100;

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("cpuUsage", Math.round(ProcessHandle.current().info().totalCpuDuration()
                    .map(d -> d.toMillis() / 1000.0).orElse(0.0)));
            resp.put("memoryUsage", Math.round(memPct * 100.0) / 100.0);
            resp.put("avgTradeLatency", avgLatency);
            resp.put("brokerStatus", List.of());
            resp.put("activeWebSocketConnections", 0);
            return resp;
        });
    }

    // 2.11 Get all subscriptions
    public Mono<Map<String, Object>> getSubscriptions(UUID masterId, String status) {
        Flux<com.copytrading.subscription.Subscription> flux;
        if (masterId != null) {
            flux = subscriptionRepo.findByMasterId(masterId);
        } else {
            flux = subscriptionRepo.findAll();
        }
        return flux.collectList().map(subs -> {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("subscriptions", subs);
            return resp;
        });
    }

    // 2.12 Get all trade logs
    public Mono<Map<String, Object>> getTradeLogs(UUID userId, String status) {
        Flux<com.copytrading.logs.CopyLog> flux;
        if (userId != null) {
            flux = copyLogRepo.findByMasterId(userId);
        } else {
            flux = copyLogRepo.findAll();
        }
        return flux.collectList().map(logs -> {
            List<?> filtered = status != null
                    ? logs.stream().filter(l -> status.equalsIgnoreCase(l.getChildStatus()) || status.equalsIgnoreCase(l.getMasterStatus())).toList()
                    : logs;
            List<Map<String, Object>> mappedLogs = filtered.stream()
                .map(l -> (com.copytrading.logs.CopyLog) l)
                .sorted((a, b) -> {
                    java.time.Instant tA = a.getCreatedAt() != null ? a.getCreatedAt() : java.time.Instant.EPOCH;
                    java.time.Instant tB = b.getCreatedAt() != null ? b.getCreatedAt() : java.time.Instant.EPOCH;
                    return tB.compareTo(tA);
                })
                .map(cl -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", cl.getId());
                    map.put("masterId", cl.getMasterId());
                    map.put("childId", cl.getChildId());
                    map.put("type", "REPLICATED");
                    map.put("action", cl.getTradeType() != null ? cl.getTradeType() : "BUY");
                    map.put("symbol", cl.getSymbol());
                    map.put("qty", cl.getChildQty() != null ? cl.getChildQty() : cl.getQty());
                    map.put("price", cl.getPrice());
                    map.put("status", cl.getChildStatus() != null ? cl.getChildStatus() : cl.getMasterStatus());
                    map.put("message", cl.getErrorMessage() != null ? cl.getErrorMessage() : cl.getSkipReason());
                    map.put("createdAt", cl.getCreatedAt() != null ? cl.getCreatedAt() : cl.getChildPlacedAt());
                    return map;
                }).toList();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("logs", mappedLogs);
            return resp;
        });
    }

    // 2.13 Master-child map (all masters with their linked children)
    public Mono<Map<String, Object>> getMasterChildMap() {
        return users.findByRole("MASTER").collectList().flatMap(masters -> {
            return Flux.fromIterable(masters).flatMap(master ->
                subscriptionRepo.findByMasterId(master.getId())
                    .filter(s -> "ACTIVE".equals(s.getCopyingStatus())
                            || "PAUSED".equals(s.getCopyingStatus())
                            || "PENDING_APPROVAL".equals(s.getCopyingStatus()))
                    .filter(s -> !s.getChildId().equals(master.getId()))
                    .flatMap(s -> users.findById(s.getChildId())
                        .map(child -> {
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("childId", child.getId().toString());
                            c.put("name", child.getName());
                            c.put("email", child.getEmail());
                            c.put("status", s.getCopyingStatus());
                            c.put("scalingFactor", s.getScalingFactor());
                            return c;
                        }))
                    .collectList()
                    .map(children -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("masterId", master.getId().toString());
                        m.put("masterName", master.getName());
                        m.put("masterEmail", master.getEmail());
                        m.put("children", children);
                        return m;
                    })
            ).collectList().map(list -> {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("masters", list);
                resp.put("total", list.size());
                return resp;
            });
        });
    }
}
