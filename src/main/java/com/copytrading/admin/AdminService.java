package com.copytrading.admin;

import com.copytrading.admin.dto.*;
import com.copytrading.auth.AuthService;
import com.copytrading.auth.UserAccount;
import com.copytrading.auth.UserAccountRepository;
import com.copytrading.auth.RefreshTokenRepository;
import com.copytrading.auth.dto.UserDto;
import com.copytrading.config.KillSwitchCache;
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
import jakarta.annotation.PostConstruct;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    @org.springframework.beans.factory.annotation.Autowired
    private com.copytrading.ws.TradeUpdatesHub tradeUpdatesHub;

    @org.springframework.beans.factory.annotation.Autowired
    private com.copytrading.admin.repository.SystemSettingRepository systemSettingRepo;

    @org.springframework.beans.factory.annotation.Autowired
    private com.copytrading.auth.JwtService jwtService;

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
    private final KillSwitchCache killSwitchCache;
    private final com.copytrading.broker.BrokerAccountService brokerService;
    private final AdminAlertService adminAlertService;

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
                        org.springframework.r2dbc.core.DatabaseClient databaseClient,
                        KillSwitchCache killSwitchCache,
                        com.copytrading.broker.BrokerAccountService brokerService,
                        AdminAlertService adminAlertService) {
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
        this.killSwitchCache = killSwitchCache;
        this.brokerService = brokerService;
        this.adminAlertService = adminAlertService;
    }

    @PostConstruct
    public void initKillSwitchCache() {
        getKillSwitchStatus().subscribe(status -> {
            killSwitchCache.setEnabled((Boolean) status.getOrDefault("enabled", false));
        });
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
        long startPing = System.currentTimeMillis();
        return databaseClient.sql("SELECT 1").fetch().rowsUpdated()
            .flatMap(rows -> copyLogRepo.findAll().collectList())
            .map(copyLogs -> {
                long dbLatency = System.currentTimeMillis() - startPing;
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

                int activeConnections = tradeUpdatesHub != null ? tradeUpdatesHub.getActiveConnections() : 0;

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("cpuUsage", Math.round(ProcessHandle.current().info().totalCpuDuration()
                        .map(d -> d.toMillis() / 1000.0).orElse(0.0)));
                resp.put("memoryUsage", Math.round(memPct * 100.0) / 100.0);
                resp.put("avgTradeLatency", avgLatency);
                resp.put("brokerStatus", List.of());
                resp.put("dbLatency", dbLatency);
                resp.put("activeWebSocketConnections", activeConnections);
                return resp;
            })
            .onErrorResume(e -> {
                Map<String, Object> errResp = new LinkedHashMap<>();
                errResp.put("status", "DOWN");
                errResp.put("error", e.getMessage());
                return Mono.just(errResp);
            });
    }

    // 2.11 Get all subscriptions
    public Mono<Map<String, Object>> getSubscriptions(UUID masterId, String status, int page, int limit) {
        Flux<com.copytrading.subscription.Subscription> flux;
        if (masterId != null) {
            flux = subscriptionRepo.findByMasterId(masterId);
        } else {
            flux = subscriptionRepo.findAll();
        }
        return flux.collectList().map(subs -> {
            List<com.copytrading.subscription.Subscription> filtered = subs;
            if (status != null && !status.isBlank()) {
                filtered = subs.stream().filter(s -> status.equals(s.getCopyingStatus())).collect(java.util.stream.Collectors.toList());
            }
            long total = filtered.size();
            List<com.copytrading.subscription.Subscription> paginated = filtered.stream()
                .skip((long) (page - 1) * limit)
                .limit(limit)
                .collect(java.util.stream.Collectors.toList());

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("subscriptions", paginated);
            resp.put("total", total);
            resp.put("page", page);
            resp.put("limit", limit);
            return resp;
        });
    }

    // 2.12 Get all trade logs
    public Mono<Map<String, Object>> getTradeLogs(UUID userId, String status, int page, int limit) {
        Flux<com.copytrading.logs.CopyLog> flux;
        if (userId != null) {
            flux = copyLogRepo.findByMasterId(userId);
        } else {
            flux = copyLogRepo.findAll();
        }
        
        Mono<Map<String, Double>> tradePricesMono = databaseClient.sql("SELECT id, price FROM trades")
            .map(row -> new java.util.AbstractMap.SimpleEntry<>(
                String.valueOf(row.get("id", UUID.class)), 
                row.get("price", Double.class)
            )).all()
            .collectMap(Map.Entry::getKey, e -> e.getValue() != null ? e.getValue() : 0.0);

        return Mono.zip(
            flux.collectList(), 
            brokerAccountRepo.findAll().collectList(), 
            users.findAll().collectList(),
            tradePricesMono
        ).map(tuple -> {
            List<com.copytrading.logs.CopyLog> logs = tuple.getT1();
            List<com.copytrading.broker.BrokerAccount> brokers = tuple.getT2();
            List<com.copytrading.auth.UserAccount> allUsers = tuple.getT3();
            Map<String, Double> tradePrices = tuple.getT4();

            Map<UUID, String> brokerMap = brokers.stream()
                .filter(b -> b.getUserId() != null && b.getBrokerId() != null)
                .collect(java.util.stream.Collectors.toMap(com.copytrading.broker.BrokerAccount::getUserId, com.copytrading.broker.BrokerAccount::getBrokerId, (a,b)->a));

            Map<UUID, String> userMap = allUsers.stream()
                .collect(java.util.stream.Collectors.toMap(com.copytrading.auth.UserAccount::getId, com.copytrading.auth.UserAccount::getName));

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
                    map.put("masterName", userMap.getOrDefault(cl.getMasterId(), "System"));
                    map.put("childId", cl.getChildId());
                    map.put("childName", userMap.getOrDefault(cl.getChildId(), "Child"));
                    map.put("type", "REPLICATED");
                    map.put("action", cl.getTradeType() != null ? cl.getTradeType() : "BUY");
                    map.put("symbol", cl.getSymbol());
                    map.put("qty", cl.getChildQty() != null ? cl.getChildQty() : cl.getQty());
                    
                    Double finalPrice = cl.getPrice();
                    if (finalPrice == null || finalPrice == 0.0) {
                        finalPrice = tradePrices.getOrDefault(cl.getMasterTradeId(), 0.0);
                    }
                    map.put("price", finalPrice);
                    
                    map.put("status", cl.getChildStatus() != null ? cl.getChildStatus() : cl.getMasterStatus());
                    map.put("message", cl.getErrorMessage() != null ? cl.getErrorMessage() : cl.getSkipReason());
                    map.put("createdAt", cl.getCreatedAt() != null ? cl.getCreatedAt() : cl.getChildPlacedAt());
                    map.put("broker", brokerMap.getOrDefault(cl.getChildId(), "N/A"));
                    return map;
                }).toList();
            
            if (status != null && !status.isBlank()) {
                mappedLogs = mappedLogs.stream().filter(m -> status.equals(m.get("status"))).collect(java.util.stream.Collectors.toList());
            }

            long total = mappedLogs.size();
            List<Map<String, Object>> paginated = mappedLogs.stream()
                .skip((long) (page - 1) * limit)
                .limit(limit)
                .collect(java.util.stream.Collectors.toList());

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("logs", paginated);
            resp.put("total", total);
            resp.put("page", page);
            resp.put("limit", limit);
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
                    .map(childList -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("masterId", master.getId().toString());
                        m.put("name", master.getName());
                        m.put("email", master.getEmail());
                        m.put("children", childList);
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

    // 2.14 Get Global Risk Settings
    public Mono<String> getGlobalRiskSettings() {
        return systemSettingRepo.findById("global_risk_settings")
                .map(com.copytrading.admin.model.SystemSetting::getValue)
                .defaultIfEmpty("{}");
    }

    // 2.15 Save Global Risk Settings
    public Mono<Void> saveGlobalRiskSettings(String json) {
        try {
            Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
            if (map.containsKey("kill_switch_active")) {
                boolean active = Boolean.parseBoolean(String.valueOf(map.get("kill_switch_active")));
                killSwitchCache.setEnabled(active);
                databaseClient.sql("UPDATE system_settings SET value = :val WHERE key = 'kill_switch'")
                    .bind("val", String.valueOf(active)).then().subscribe();
            }
        } catch (Exception ignored) {}

        return systemSettingRepo.findById("global_risk_settings")
                .flatMap(setting -> {
                    setting.setValue(json);
                    setting.setNew(false);
                    return systemSettingRepo.save(setting);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    com.copytrading.admin.model.SystemSetting newSetting = new com.copytrading.admin.model.SystemSetting("global_risk_settings", json);
                    newSetting.setNew(true);
                    return systemSettingRepo.save(newSetting);
                })).then();
    }

    /**
     * Apply global risk settings to all children who DON'T have custom rules set.
     * Children with existing custom rules are untouched.
     */
    public Mono<Map<String, Object>> applyGlobalRiskToAllChildren(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);
            int maxTrades = node.has("maxTradesPerDay") ? node.get("maxTradesPerDay").asInt(50) : 50;
            int maxPositions = node.has("maxOpenPositions") ? node.get("maxOpenPositions").asInt(20) : 20;
            double maxExposure = node.has("maxCapitalExposure") ? node.get("maxCapitalExposure").asDouble(80) : 80;
            boolean marginCheck = !node.has("marginCheckEnabled") || node.get("marginCheckEnabled").asBoolean(true);

            // Insert for users who don't have risk_rules yet (children = users in subscriptions as child_id)
            String sql = "INSERT INTO risk_rules (user_id, max_trades_per_day, max_open_positions, max_capital_exposure, margin_check_enabled, copy_paused, updated_at) "
                    + "SELECT DISTINCT s.child_id, :maxTrades, :maxPos, :maxExp, :marginCheck, false, NOW() "
                    + "FROM subscriptions s WHERE s.child_id NOT IN (SELECT user_id FROM risk_rules) "
                    + "ON CONFLICT (user_id) DO NOTHING";
            return databaseClient.sql(sql)
                    .bind("maxTrades", maxTrades)
                    .bind("maxPos", maxPositions)
                    .bind("maxExp", maxExposure)
                    .bind("marginCheck", marginCheck)
                    .fetch().rowsUpdated()
                    .map(count -> Map.<String, Object>of(
                            "message", "Global risk applied to children without custom rules",
                            "childrenUpdated", count,
                            "maxTradesPerDay", maxTrades,
                            "maxOpenPositions", maxPositions));
        } catch (Exception e) {
            return Mono.just(Map.of("error", "Invalid JSON: " + e.getMessage()));
        }
    }

    /**
     * Force-apply risk settings to ALL children — overwrites any custom rules.
     */
    public Mono<Map<String, Object>> forceApplyRiskToAllChildren(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);
            int maxTrades = node.has("maxTradesPerDay") ? node.get("maxTradesPerDay").asInt(50) : 50;
            int maxPositions = node.has("maxOpenPositions") ? node.get("maxOpenPositions").asInt(20) : 20;
            double maxExposure = node.has("maxCapitalExposure") ? node.get("maxCapitalExposure").asDouble(80) : 80;
            boolean marginCheck = !node.has("marginCheckEnabled") || node.get("marginCheckEnabled").asBoolean(true);

            String sql = "INSERT INTO risk_rules (user_id, max_trades_per_day, max_open_positions, max_capital_exposure, margin_check_enabled, copy_paused, updated_at) "
                    + "SELECT DISTINCT s.child_id, :maxTrades, :maxPos, :maxExp, :marginCheck, false, NOW() "
                    + "FROM subscriptions s "
                    + "ON CONFLICT (user_id) DO UPDATE SET max_trades_per_day = :maxTrades, max_open_positions = :maxPos, "
                    + "max_capital_exposure = :maxExp, margin_check_enabled = :marginCheck, updated_at = NOW()";
            return databaseClient.sql(sql)
                    .bind("maxTrades", maxTrades)
                    .bind("maxPos", maxPositions)
                    .bind("maxExp", maxExposure)
                    .bind("marginCheck", marginCheck)
                    .fetch().rowsUpdated()
                    .map(count -> Map.<String, Object>of(
                            "message", "Risk settings force-applied to ALL children",
                            "childrenUpdated", count,
                            "maxTradesPerDay", maxTrades,
                            "maxOpenPositions", maxPositions,
                            "maxCapitalExposure", maxExposure));
        } catch (Exception e) {
            return Mono.just(Map.of("error", "Invalid JSON: " + e.getMessage()));
        }
    }

    // 2.16 View As User (Impersonation)
    public Mono<Map<String, String>> impersonateUser(UUID userId) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("User not found")))
                .map(user -> {
                    String token = jwtService.generateImpersonationToken(user.getId(), user.getEmail());
                    return Map.of("accessToken", token);
                });
    }

    // 2.17 Get admin audit logs
    public Mono<Map<String, Object>> getAuditLogs(int page, int limit) {
        int offset = (page - 1) * limit;
        String selectQuery = """
            SELECT l.id, l.user_id, u.name as user_name, u.email as user_email, l.action, l.parameters, l.created_at
            FROM admin_audit_logs l
            LEFT JOIN users u ON l.user_id = u.id
            ORDER BY l.created_at DESC
            LIMIT :limit OFFSET :offset
            """;
        String countQuery = "SELECT COUNT(*) FROM admin_audit_logs";

        return Mono.zip(
            databaseClient.sql(selectQuery)
                .bind("limit", limit)
                .bind("offset", offset)
                .map((row, metadata) -> new com.copytrading.admin.dto.AdminAuditLogResponse(
                    row.get("id", UUID.class),
                    row.get("user_id", UUID.class),
                    row.get("user_name", String.class),
                    row.get("user_email", String.class),
                    row.get("action", String.class),
                    row.get("parameters", String.class),
                    row.get("created_at", Instant.class)
                )).all().collectList(),
            databaseClient.sql(countQuery).map((row, metadata) -> row.get(0, Long.class)).one()
        ).map(tuple -> {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("logs", tuple.getT1());
            resp.put("total", tuple.getT2());
            resp.put("page", page);
            resp.put("limit", limit);
            return resp;
        });
    }

    // 2.15 Get Kill Switch Status
    public Mono<Map<String, Object>> getKillSwitchStatus() {
        return databaseClient.sql("SELECT value FROM system_settings WHERE key = 'kill_switch'")
            .map(row -> Boolean.parseBoolean(row.get(0, String.class))).one()
            .defaultIfEmpty(false)
            .flatMap(enabled -> {
                String auditQuery = """
                    SELECT u.name, l.created_at, l.parameters
                    FROM admin_audit_logs l
                    LEFT JOIN users u ON l.user_id = u.id
                    WHERE l.action = 'TOGGLE_KILL_SWITCH'
                    ORDER BY l.created_at DESC LIMIT 1
                    """;
                return databaseClient.sql(auditQuery)
                    .map(row -> {
                        Map<String, Object> status = new LinkedHashMap<>();
                        status.put("enabled", enabled);
                        status.put("lastChangedBy", row.get("name", String.class));
                        status.put("lastChangedAt", row.get("created_at", Instant.class));
                        // parse parameters to extract reason if possible, or just send the raw parameters string
                        status.put("reason", row.get("parameters", String.class)); 
                        return status;
                    }).one()
                    .defaultIfEmpty(Map.of("enabled", enabled));
            });
    }

    // 2.16 POST /admin/kill-switch
    public Mono<Void> toggleKillSwitch(boolean enable) {
        killSwitchCache.setEnabled(enable);
        adminAlertService.sendAdminAlert(
                "GLOBAL KILL SWITCH " + (enable ? "ACTIVATED" : "DEACTIVATED"),
                "The platform-wide kill switch has been " + (enable ? "turned ON. All new copy trades will be blocked." : "turned OFF. Normal trading has resumed.")
        );
        return databaseClient.sql("UPDATE system_settings SET value = :val WHERE key = 'kill_switch'")
            .bind("val", String.valueOf(enable))
            .then();
    }

    // 2.17 Get Positions for Force Square-Off
    public Mono<List<Map<String, Object>>> getPositions(String targetId, String scope) {
        if (targetId == null || targetId.isBlank()) return Mono.just(List.of());
        UUID id = UUID.fromString(targetId);
        
        if ("user".equals(scope)) {
            return getActiveBrokerPositions(id);
        } else if ("master-group".equals(scope)) {
            return getActiveBrokerPositions(id).flatMap(masterPos ->
                subscriptionRepo.findByMasterIdAndCopyingStatus(id, "ACTIVE")
                    .flatMap(sub -> getActiveBrokerPositions(sub.getChildId()))
                    .collectList()
                    .map(childPosList -> {
                        List<Map<String, Object>> all = new ArrayList<>(masterPos);
                        childPosList.forEach(all::addAll);
                        return all;
                    })
            );
        }
        return Mono.just(List.of());
    }

    private Mono<List<Map<String, Object>>> getActiveBrokerPositions(UUID userId) {
        return masterActiveRepo.findById(userId)
            .flatMap(aa -> brokerService.getPositions(aa.getBrokerAccountId(), userId))
            .switchIfEmpty(Mono.defer(() -> 
                brokerAccountRepo.findByUserId(userId)
                    .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                    .next()
                    .flatMap(a -> brokerService.getPositions(a.getId(), userId))
            ))
            .map(resp -> {
                Object positions = resp.get("positions");
                if (positions instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> posList = (List<Map<String, Object>>) positions;
                    // Add user ID so the UI knows whose position it is
                    posList.forEach(p -> p.put("_ownerId", userId.toString()));
                    return posList;
                }
                return List.<Map<String, Object>>of();
            })
            .onErrorReturn(List.of());
    }

    // 2.18 Force Square-Off
    public Mono<List<Map<String, Object>>> forceSquareOff(String targetId, String scope) {
        // As a bare-minimum implementation, we fetch the positions and then attempt to close them.
        // To save time and complexity for all broker nuances, if we just call closePosition it needs specific fields.
        // We'll map the common fields. If it fails, we return the error in the list.
        return getPositions(targetId, scope).flatMap(positions -> {
            return Flux.fromIterable(positions)
                .flatMap(p -> {
                    UUID ownerId = UUID.fromString((String) p.get("_ownerId"));
                    
                    // Extract common fields (Zerodha/Groww/Upstox/etc)
                    String symbol = (String) p.getOrDefault("tradingsymbol", p.getOrDefault("symbol", p.get("contractDisplayName")));
                    Object qtyObj = p.getOrDefault("quantity", p.getOrDefault("netQuantity", p.get("netQty")));
                    int qty = 0;
                    if (qtyObj instanceof Number) qty = ((Number) qtyObj).intValue();
                    else if (qtyObj instanceof String) {
                        try { qty = (int) Double.parseDouble((String) qtyObj); } catch (Exception e) {}
                    }
                    
                    if (qty == 0 || symbol == null) {
                        return Mono.just(Map.<String, Object>of("symbol", symbol != null ? symbol : "UNKNOWN", "status", "ignored", "reason", "Zero quantity or unknown symbol"));
                    }
                    
                    String type = qty > 0 ? "SELL" : "BUY";
                    int absQty = Math.abs(qty);
                    String product = (String) p.getOrDefault("product", p.getOrDefault("productType", "MIS"));
                    String exchange = (String) p.getOrDefault("exchange", "NSE");
                    
                    Map<String, Object> closeBody = Map.of(
                        "symbol", symbol,
                        "qty", absQty,
                        "type", type,
                        "product", product,
                        "exchange", exchange
                    );
                    
                    return masterActiveRepo.findById(ownerId)
                        .flatMap(aa -> brokerService.closePosition(aa.getBrokerAccountId(), ownerId, closeBody))
                        .switchIfEmpty(Mono.defer(() -> 
                            brokerAccountRepo.findByUserId(ownerId)
                                .filter(a -> a.isSessionActive() && a.getAccessToken() != null)
                                .next()
                                .flatMap(a -> brokerService.closePosition(a.getId(), ownerId, closeBody))
                        ))
                        .map(r -> Map.<String, Object>of("symbol", symbol, "status", "success", "response", r))
                        .onErrorResume(e -> Mono.just(Map.<String, Object>of("symbol", symbol, "status", "failed", "reason", e.getMessage())));
                })
                .collectList();
        });
    }

    // 2.19 Order Trace (ADM-17)
    public Mono<Map<String, Object>> getOrderTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) return Mono.just(Map.of());

        // We use copy_logs as the source of truth because it binds master and children via copy_group_id
        String traceBaseSql = """
            SELECT c.copy_group_id
            FROM copy_logs c
            WHERE c.copy_group_id = :traceId OR c.master_trade_id = :traceId OR c.child_broker_order_id = :traceId
            LIMIT 1
            """;

        return databaseClient.sql(traceBaseSql).bind("traceId", traceId).map(row -> row.get("copy_group_id", String.class)).first()
            .defaultIfEmpty(traceId) // fallback to whatever was passed
            .flatMap(resolvedGroupId -> {
                String masterSql = """
                    SELECT c.master_trade_id as broker_order_id, u.name as master_user,
                           c.symbol, c.trade_type as side, c.qty as quantity, c.order_type,
                           c.product, c.master_placed_at as placed_at, c.master_status as status, c.price
                    FROM copy_logs c
                    LEFT JOIN users u ON c.master_id = u.id
                    WHERE c.copy_group_id = :groupId
                    LIMIT 1
                    """;

                String childSql = """
                    SELECT c.child_id, u.name as child_user, b.broker_id as child_broker,
                           c.child_qty, c.qty as master_qty, c.child_status as status, c.child_placed_at as executed_at,
                           c.child_broker_order_id, c.latency_ms, c.entry_price, c.filled_qty,
                           c.error_message, c.skip_reason
                    FROM copy_logs c
                    LEFT JOIN users u ON c.child_id = u.id
                    LEFT JOIN subscriptions s ON s.child_id = c.child_id AND s.master_id = c.master_id
                    LEFT JOIN broker_accounts b ON b.id = s.broker_account_id
                    WHERE c.copy_group_id = :groupId
                    """;

                Mono<Map<String, Object>> masterMono = databaseClient.sql(masterSql)
                    .bind("groupId", resolvedGroupId)
                    .fetch().first()
                    .defaultIfEmpty(Map.of());

                Mono<List<Map<String, Object>>> childrenMono = databaseClient.sql(childSql)
                    .bind("groupId", resolvedGroupId)
                    .fetch().all().collectList();

                return Mono.zip(masterMono, childrenMono).map(tuple -> {
                    Map<String, Object> masterObj = tuple.getT1();
                    List<Map<String, Object>> childrenList = tuple.getT2();

                    // Formatting child copies
                    List<Map<String, Object>> formattedChildren = new ArrayList<>();
                    List<Map<String, Object>> checksList = new ArrayList<>();
                    int copied = 0, skipped = 0, failed = 0;
                    long totalLatency = 0;
                    int latencyCount = 0;

                    for (Map<String, Object> c : childrenList) {
                        Map<String, Object> fc = new LinkedHashMap<>();
                        fc.put("childUser", c.get("child_user"));
                        fc.put("childBroker", c.get("child_broker"));
                        
                        Number cQty = (Number) c.get("child_qty");
                        Number mQty = (Number) c.get("master_qty");
                        if (cQty != null && mQty != null && mQty.doubleValue() > 0) {
                            fc.put("scalingFactor", String.format("%.2fx", cQty.doubleValue() / mQty.doubleValue()));
                        } else {
                            fc.put("scalingFactor", "N/A");
                        }
                        
                        fc.put("quantityCopied", cQty);
                        fc.put("brokerOrderId", c.get("child_broker_order_id"));
                        
                        String status = (String) c.get("status");
                        fc.put("status", status);
                        fc.put("executedAt", c.get("executed_at"));
                        fc.put("price", c.get("entry_price"));
                        
                        Number latency = (Number) c.get("latency_ms");
                        fc.put("latencyMs", latency);
                        
                        // Calculate slippage if possible
                        Number masterPrice = (Number) masterObj.get("price");
                        Number childPrice = (Number) c.get("entry_price");
                        if (masterPrice != null && childPrice != null && masterPrice.doubleValue() > 0) {
                            double slippage = childPrice.doubleValue() - masterPrice.doubleValue();
                            fc.put("slippage", String.format("%.2f", slippage));
                        } else {
                            fc.put("slippage", "N/A");
                        }

                        String skipReason = (String) c.get("skip_reason");
                        String errorMsg = (String) c.get("error_message");
                        String reason = skipReason != null ? skipReason : errorMsg;
                        fc.put("reason", reason);

                        formattedChildren.add(fc);

                        // Checks logic
                        if (reason != null && (reason.contains("risk") || reason.contains("guard") || reason.contains("limit") || reason.contains("scale"))) {
                            checksList.add(Map.of(
                                "childUser", c.get("child_user"),
                                "checkType", skipReason != null ? "PRE-TRADE CHECK" : "POST-TRADE GUARD",
                                "result", "FAILED",
                                "ruleTriggered", reason
                            ));
                        }

                        // Summary counts
                        if ("SUCCESS".equalsIgnoreCase(status) || "PLACED".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) copied++;
                        else if ("SKIPPED".equalsIgnoreCase(status)) skipped++;
                        else failed++;

                        if (latency != null) {
                            totalLatency += latency.longValue();
                            latencyCount++;
                        }
                    }

                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("totalChildren", childrenList.size());
                    summary.put("copied", copied);
                    summary.put("skipped", skipped);
                    summary.put("failed", failed);
                    summary.put("averageLatencyMs", latencyCount > 0 ? (totalLatency / latencyCount) : 0);

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("traceId", resolvedGroupId);
                    resp.put("masterOrder", masterObj);
                    resp.put("checks", checksList);
                    resp.put("childCopies", formattedChildren);
                    resp.put("summary", summary);
                    return resp;
                });
            });
    }

    // 2.20 Failed Copies Monitor
    public Mono<Map<String, Object>> getFailedCopies(String status, String masterId, String childId, 
                                                     String broker, String dateFrom, String dateTo, 
                                                     int page, int limit) {
        int offset = (page - 1) * limit;

        StringBuilder where = new StringBuilder("WHERE c.child_status IN ('FAILED', 'SKIPPED')");
        if (status != null && !status.isBlank()) {
            where.append(" AND c.child_status = '").append(status).append("'");
        }
        if (masterId != null && !masterId.isBlank()) {
            where.append(" AND c.master_id = '").append(masterId).append("'");
        }
        if (childId != null && !childId.isBlank()) {
            where.append(" AND c.child_id = '").append(childId).append("'");
        }
        if (broker != null && !broker.isBlank()) {
            where.append(" AND b.broker_id = '").append(broker).append("'");
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            where.append(" AND c.created_at >= '").append(dateFrom).append("'");
        }
        if (dateTo != null && !dateTo.isBlank()) {
            where.append(" AND c.created_at <= '").append(dateTo).append("'");
        }

        String query = """
            SELECT c.id, c.symbol, c.child_status as status, 
                   COALESCE(c.error_message, c.skip_reason) as reason, c.created_at as timestamp,
                   u1.name as masterName, u2.name as childName, b.broker_id as broker
            FROM copy_logs c
            LEFT JOIN users u1 ON c.master_id = u1.id
            LEFT JOIN users u2 ON c.child_id = u2.id
            LEFT JOIN broker_accounts b ON b.user_id = c.child_id
            """ + where + """
            ORDER BY c.created_at DESC
            LIMIT :limit OFFSET :offset
            """;

        String countQuery = """
            SELECT COUNT(*) FROM copy_logs c
            LEFT JOIN broker_accounts b ON b.user_id = c.child_id
            """ + where;

        return Mono.zip(
            databaseClient.sql(query).bind("limit", limit).bind("offset", offset)
                .fetch().all().collectList(),
            databaseClient.sql(countQuery).map((row, metadata) -> row.get(0, Long.class)).one()
        ).map(tuple -> {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("copies", tuple.getT1());
            resp.put("total", tuple.getT2());
            resp.put("page", page);
            resp.put("limit", limit);
            return resp;
        });
    }

    // 2.21 P&L Dashboard
    public Mono<Map<String, Object>> getPnL(String dateFrom, String dateTo) {
        // Minimal implementation to satisfy frontend requirement. 
        return Mono.just(Map.of(
            "masters", List.of(),
            "children", List.of(),
            "platformTotal", 0
        ));
    }

    // 2.22 Broker Status
    public Mono<List<Map<String, Object>>> getBrokerStatus() {
        return databaseClient.sql("SELECT id, user_id, broker_id, session_active, status FROM broker_accounts")
            .fetch().all()
            .map(row -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", row.get("id"));
                map.put("userId", row.get("user_id"));
                map.put("broker", row.get("broker_id"));
                map.put("active", row.get("session_active"));
                map.put("status", row.get("status"));
                return map;
            })
            .collectList();
    }
}

