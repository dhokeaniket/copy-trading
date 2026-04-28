package com.copytrading.admin;

import com.copytrading.admin.dto.*;
import com.copytrading.auth.dto.UserDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "6. Admin", description = "Admin: user management, analytics, system health, subscriptions, trade logs")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
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
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> createMaster(@RequestBody CreateMasterRequest req) {
        return adminService.createMaster(req);
    }

    // 2.3 POST /admin/users/child
    @PostMapping(value = "/users/child", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, Object>> createChild(@RequestBody CreateChildRequest req) {
        return adminService.createChild(req);
    }

    // 2.4 GET /admin/users/:userId
    @GetMapping("/users/{userId}")
    public Mono<UserDto> getUser(@PathVariable UUID userId) {
        return adminService.getUserById(userId);
    }

    // 2.5 PUT /admin/users/:userId
    @PutMapping(value = "/users/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<UserDto> updateUser(@PathVariable UUID userId,
                                     @RequestBody UpdateUserRequest req) {
        return adminService.updateUser(userId, req);
    }

    // 2.6 PATCH /admin/users/:userId/activate
    @PatchMapping("/users/{userId}/activate")
    public Mono<Map<String, String>> activateUser(@PathVariable UUID userId) {
        return adminService.activateUser(userId);
    }

    // 2.7 PATCH /admin/users/:userId/deactivate
    @PatchMapping("/users/{userId}/deactivate")
    public Mono<Map<String, String>> deactivateUser(@PathVariable UUID userId) {
        return adminService.deactivateUser(userId);
    }

    // 2.8 DELETE /admin/users/:userId
    @DeleteMapping("/users/{userId}")
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
            @RequestParam(required = false) String status) {
        return adminService.getSubscriptions(masterId, status);
    }

    // 2.12 GET /admin/trade-logs
    @GetMapping("/trade-logs")
    public Mono<Map<String, Object>> getTradeLogs(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String status) {
        return adminService.getTradeLogs(userId, status);
    }
}
