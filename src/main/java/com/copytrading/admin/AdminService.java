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

    public AdminService(UserAccountRepository users,
                        RefreshTokenRepository refreshTokens,
                        AuthService authService,
                        SubscriptionRepository subscriptionRepo,
                        TradeLogRepository tradeLogRepo) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.authService = authService;
        this.subscriptionRepo = subscriptionRepo;
        this.tradeLogRepo = tradeLogRepo;
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
        return flux.collectList().map(all -> {
            int total = all.size();
            int start = (page - 1) * limit;
            int end = Math.min(start + limit, total);
            List<UserDto> pageData = start < total
                    ? all.subList(start, end).stream().map(UserDto::from).toList()
                    : List.of();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("users", pageData);
            resp.put("total", total);
            resp.put("page", page);
            return resp;
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

    // 2.8 Delete user
    public Mono<Map<String, String>> deleteUser(UUID userId) {
        return users.findById(userId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .flatMap(u -> {
                    log.warn("ADMIN_DELETE_USER id={} email={}", u.getId(), u.getEmail());
                    return refreshTokens.revokeAllByUserId(userId)
                            .then(users.deleteById(userId))
                            .thenReturn(Map.of("message", "User permanently deleted"));
                });
    }

    // 2.9 Analytics
    public Mono<Map<String, Object>> getAnalytics() {
        return Mono.zip(
                users.countByRole("ADMIN"),
                users.countByRole("MASTER"),
                users.countByRole("CHILD"),
                subscriptionRepo.findAll().collectList(),
                tradeLogRepo.findAll().collectList()
        ).map(tuple -> {
            Map<String, Object> totalUsers = new LinkedHashMap<>();
            totalUsers.put("admin", tuple.getT1());
            totalUsers.put("master", tuple.getT2());
            totalUsers.put("child", tuple.getT3());

            var subs = tuple.getT4();
            var logs = tuple.getT5();
            long activeSubs = subs.stream().filter(s -> "ACTIVE".equals(s.getCopyingStatus())).count();
            long totalTrades = logs.stream().filter(l -> "EXECUTED".equals(l.getStatus())).count();
            long totalReplications = logs.stream().filter(l -> "REPLICATED".equals(l.getType())).count();

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("totalUsers", totalUsers);
            resp.put("totalTrades", totalTrades);
            resp.put("totalReplications", totalReplications);
            resp.put("tradeVolume", 0);
            resp.put("activeSubscriptions", activeSubs);
            return resp;
        });
    }

    // 2.10 System health
    public Mono<Map<String, Object>> getSystemHealth() {
        Runtime rt = Runtime.getRuntime();
        long maxMem = rt.maxMemory();
        long usedMem = rt.totalMemory() - rt.freeMemory();
        double memPct = (double) usedMem / maxMem * 100;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("cpuUsage", Math.round(ProcessHandle.current().info().totalCpuDuration()
                .map(d -> d.toMillis() / 1000.0).orElse(0.0)));
        resp.put("memoryUsage", Math.round(memPct * 100.0) / 100.0);
        resp.put("avgTradeLatency", 0);
        resp.put("brokerStatus", List.of());
        resp.put("activeWebSocketConnections", 0);
        return Mono.just(resp);
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
        Flux<com.copytrading.logs.TradeLog> flux;
        if (userId != null) {
            flux = tradeLogRepo.findByMasterId(userId);
        } else {
            flux = tradeLogRepo.findAll();
        }
        return flux.collectList().map(logs -> {
            List<?> filtered = status != null
                    ? logs.stream().filter(l -> status.equalsIgnoreCase(l.getStatus())).toList()
                    : logs;
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("logs", filtered);
            return resp;
        });
    }
}
