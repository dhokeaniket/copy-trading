package com.copytrading.admin;

import com.copytrading.admin.aspect.AdminAudit;
import com.copytrading.admin.dto.*;
import com.copytrading.auth.dto.UserDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "6. Admin", description = "Admin: user management, analytics, system health, subscriptions, trade logs")
public class AdminController {

    private final AdminService adminService;
    private final org.springframework.r2dbc.core.DatabaseClient databaseClient;

    public AdminController(AdminService adminService, org.springframework.r2dbc.core.DatabaseClient databaseClient) {
        this.adminService = adminService;
        this.databaseClient = databaseClient;
    }

    @GetMapping("/fix-db")
    @Operation(summary = "TEMPORARY FIX", description = "Executes missing schema updates")
    public Mono<String> fixDatabase() {
        String sql = "ALTER TABLE copy_logs ADD COLUMN IF NOT EXISTS entry_price NUMERIC; " +
                     "ALTER TABLE copy_logs ADD COLUMN IF NOT EXISTS filled_qty INTEGER; " +
                     "ALTER TABLE copy_logs ADD COLUMN IF NOT EXISTS invested_value NUMERIC; " +
                     "CREATE INDEX IF NOT EXISTS idx_copy_logs_pending_entry ON copy_logs(child_status) WHERE child_status = 'PLACED' AND entry_price IS NULL;";
        return databaseClient.sql(sql).then().thenReturn("Database fixed successfully! 500 Errors should be gone now.");
    }

    // 2.1 GET /admin/users
    @GetMapping("/users")
    public Mono<Map<String, Object>> listUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return adminService.listUsers(role, status, page, Math.min(limit, 100));
    }

    // 2.2 POST /admin/users/master
    @PostMapping(value = "/users/master", consumes = MediaType.APPLICATION_JSON_VALUE)
    @AdminAudit(action = "CREATE_MASTER")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> createMaster(@RequestBody CreateMasterRequest req) {
        return adminService.createMaster(req);
    }

    // 2.3 POST /admin/users/child
    @PostMapping(value = "/users/child", consumes = MediaType.APPLICATION_JSON_VALUE)
    @AdminAudit(action = "CREATE_CHILD")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> createChild(@RequestBody CreateChildRequest req) {
        return adminService.createChild(req);
    }

    // 2.3b POST /admin/users/admin
    @PostMapping(value = "/users/admin", consumes = MediaType.APPLICATION_JSON_VALUE)
    @AdminAudit(action = "CREATE_ADMIN")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> createAdmin(@RequestBody CreateAdminRequest req) {
        return adminService.createAdmin(req);
    }

    // 2.4 GET /admin/users/:userId
    @GetMapping("/users/{userId}")
    public Mono<Map<String, Object>> getUser(@PathVariable UUID userId) {
        return adminService.getUserDetailsAdmin(userId);
    }

    // 2.5 PUT /admin/users/:userId/status
    @PutMapping("/users/{userId}/status")
    @AdminAudit(action = "UPDATE_USER_STATUS")
    public Mono<Map<String, Object>> updateUserStatus(@PathVariable UUID userId, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        return adminService.updateUserStatus(userId, status);
    }

    // 2.5 PUT /admin/users/:userId
    @PutMapping(value = "/users/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @AdminAudit(action = "UPDATE_USER")
    public Mono<UserDto> updateUser(@PathVariable UUID userId,
                                     @RequestBody UpdateUserRequest req) {
        return adminService.updateUser(userId, req);
    }

    // 2.6 PATCH /admin/users/:userId/activate
    @PatchMapping("/users/{userId}/activate")
    @AdminAudit(action = "ACTIVATE_USER")
    public Mono<Map<String, String>> activateUser(@PathVariable UUID userId) {
        return adminService.activateUser(userId);
    }

    // 2.7 PATCH /admin/users/:userId/deactivate
    @PatchMapping("/users/{userId}/deactivate")
    @AdminAudit(action = "DEACTIVATE_USER")
    public Mono<Map<String, String>> deactivateUser(@PathVariable UUID userId) {
        return adminService.deactivateUser(userId);
    }

    // 2.8 DELETE /admin/users/:userId
    @DeleteMapping("/users/{userId}")
    @AdminAudit(action = "DELETE_USER")
    public Mono<Map<String, String>> deleteUser(@PathVariable UUID userId) {
        return adminService.deleteUser(userId);
    }

    // 2.9 GET /admin/analytics
    @GetMapping("/analytics")
    public Mono<Map<String, Object>> getAnalytics() {
        return adminService.getAnalytics();
    }

    // 2.10 GET /admin/system-health
    @GetMapping("/system-health")
    public Mono<Map<String, Object>> getSystemHealth() {
        return adminService.getSystemHealth();
    }

    // 2.11 GET /admin/subscriptions
    @GetMapping("/subscriptions")
    public Mono<Map<String, Object>> getSubscriptions(
            @RequestParam(required = false) UUID masterId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {
        return adminService.getSubscriptions(masterId, status, page, limit);
    }

    // 2.12 GET /admin/trade-logs
    @GetMapping("/trade-logs")
    public Mono<Map<String, Object>> getTradeLogs(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {
        return adminService.getTradeLogs(userId, status, page, limit);
    }

    // 2.13 GET /admin/master-child-map
    @GetMapping("/master-child-map")
    @Operation(summary = "Get all masters with their linked children (name, email, status, scalingFactor)")
    public Mono<Map<String, Object>> getMasterChildMap() {
        return adminService.getMasterChildMap();
    }

    // 2.14 GET /admin/audit-log
    @GetMapping("/audit-log")
    public Mono<Map<String, Object>> getAuditLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return adminService.getAuditLogs(page, Math.min(limit, 100));
    }

    // 2.15 GET /admin/kill-switch
    @GetMapping("/kill-switch")
    public Mono<Map<String, Object>> getKillSwitchStatus() {
        return adminService.getKillSwitchStatus();
    }

    // 2.16 POST /admin/kill-switch
    @PostMapping("/kill-switch")
    public Mono<Void> toggleKillSwitch(@RequestBody Map<String, Object> body) {
        boolean enable = Boolean.parseBoolean(String.valueOf(body.get("enable")));
        return adminService.toggleKillSwitch(enable);
    }

    // 2.17 GET /admin/positions
    @GetMapping("/positions")
    public Mono<List<Map<String, Object>>> getPositions(
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false, defaultValue = "user") String scope) {
        return adminService.getPositions(targetId, scope);
    }

    // 2.18 POST /admin/force-square-off
    @PostMapping("/force-square-off")
    public Mono<List<Map<String, Object>>> forceSquareOff(@RequestBody Map<String, Object> body) {
        String targetId = (String) body.get("targetId");
        String scope = (String) body.getOrDefault("scope", "user");
        String confirmationText = (String) body.get("confirmationText");
        
        if (!"SQUARE OFF".equals(confirmationText)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid confirmation text"));
        }
        return adminService.forceSquareOff(targetId, scope);
    }

    // 2.19 GET /admin/trace/{id}
    @GetMapping("/trace/{id}")
    public Mono<Map<String, Object>> getOrderTrace(@PathVariable String id) {
        return adminService.getOrderTrace(id);
    }

    // 2.20 GET /admin/failed-copies
    @GetMapping("/failed-copies")
    public Mono<Map<String, Object>> getFailedCopies(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String masterId,
            @RequestParam(required = false) String childId,
            @RequestParam(required = false) String broker,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {
        return adminService.getFailedCopies(status, masterId, childId, broker, dateFrom, dateTo, page, limit);
    }

    // 2.21 GET /admin/pnl
    @GetMapping("/pnl")
    public Mono<Map<String, Object>> getPnL(
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return adminService.getPnL(dateFrom, dateTo);
    }

    // 2.21 GET /admin/broker-status
    @GetMapping("/broker-status")
    public Mono<List<Map<String, Object>>> getBrokerStatus() {
        return adminService.getBrokerStatus();
    }

    // 2.22 GET /admin/settings/risk
    @GetMapping("/settings/risk")
    public Mono<String> getGlobalRiskSettings() {
        return adminService.getGlobalRiskSettings();
    }

    // 2.23 POST /admin/settings/risk — save global defaults AND apply to all children without custom rules
    @PostMapping("/settings/risk")
    @AdminAudit(action = "UPDATE_RISK_SETTINGS")
    public Mono<Map<String, Object>> saveGlobalRiskSettings(@RequestBody String json) {
        return adminService.saveGlobalRiskSettings(json)
                .then(adminService.applyGlobalRiskToAllChildren(json));
    }

    // 2.23b POST /admin/settings/risk/apply-all — force apply to ALL children (overwrite custom rules too)
    @PostMapping("/settings/risk/apply-all")
    @AdminAudit(action = "APPLY_RISK_TO_ALL")
    @Operation(summary = "Apply risk settings to ALL children", description = "Overwrites per-user risk rules with the provided values for every child user")
    public Mono<Map<String, Object>> applyRiskToAllChildren(@RequestBody String json) {
        return adminService.forceApplyRiskToAllChildren(json);
    }

    // 2.24 GET /admin/risk/rules/{userId}
    @GetMapping("/risk/rules/{userId}")
    public Mono<Map<String, Object>> getUserRiskRules(@PathVariable UUID userId) {
        return adminService.getUserRiskRules(userId);
    }

    // 2.25 PUT /admin/risk/rules/{userId}
    @PutMapping("/risk/rules/{userId}")
    @AdminAudit(action = "UPDATE_USER_RISK_RULES")
    public Mono<Map<String, Object>> updateUserRiskRules(@PathVariable UUID userId, @RequestBody Map<String, Object> body) {
        return adminService.updateUserRiskRules(userId, body);
    }

    // 2.26 POST /admin/impersonate/{userId}
    @PostMapping("/impersonate/{userId}")
    @AdminAudit(action = "IMPERSONATE_USER")
    public Mono<Map<String, String>> impersonateUser(@PathVariable UUID userId) {
        return adminService.impersonateUser(userId);
    }
}
