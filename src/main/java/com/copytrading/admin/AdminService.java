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
            
            Instant startOfDay = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
            
            long totalTradesToday = logs.stream()
                    .filter(l -> "EXECUTED".equals(l.getStatus()))
                    .filter(l -> l.getCreatedAt() != null && !l.getCreatedAt().isBefore(startOfDay))
                    .count();
                    
            long totalReplications = logs.stream().filter(l -> "REPLICATED".equals(l.getType())).count();

            long avgLatency = 0;
            if (!copyLogs.isEmpty()) {
                avgLatency = (long) copyLogs.stream()
                        .filter(c -> c.getLatencyMs() != null && c.getLatencyMs() > 0)
                        .mapToLong(com.copytrading.logs.CopyLog::getLatencyMs)
                        .average()
                        .orElse(0.0);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("totalUsers", admins + masters + children);
            resp.put("totalAdmins", admins);
            resp.put("totalMasters", masters);
            resp.put("activeMasters", masters);
            resp.put("totalChildren", children);
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
