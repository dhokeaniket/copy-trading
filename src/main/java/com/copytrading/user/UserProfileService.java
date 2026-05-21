package com.copytrading.user;

import com.copytrading.auth.AuthService;
import com.copytrading.auth.dto.UpdateProfileRequest;
import com.copytrading.broker.BrokerAccountRepository;
import com.copytrading.broker.BrokerProfileService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Spec §4 — aggregated user profile with broker accounts. */
@Service
public class UserProfileService {

    private final AuthService authService;
    private final BrokerAccountRepository brokerRepo;
    private final BrokerProfileService brokerProfileService;

    public UserProfileService(AuthService authService, BrokerAccountRepository brokerRepo,
                              BrokerProfileService brokerProfileService) {
        this.authService = authService;
        this.brokerRepo = brokerRepo;
        this.brokerProfileService = brokerProfileService;
    }

    public Mono<Map<String, Object>> getMeProfile(UUID userId) {
        return authService.getProfile(userId)
                .flatMap(user -> brokerRepo.findByUserId(userId)
                        .flatMap(a -> brokerProfileService.getProfile(a.getId(), userId, false)
                                .onErrorResume(e -> Mono.just(Map.of(
                                        "accountId", a.getId().toString(),
                                        "broker", a.getBrokerId(),
                                        "sessionActive", a.isSessionActive(),
                                        "error", e.getMessage()))))
                        .collectList()
                        .map(accounts -> {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("userId", user.getUserId());
                            r.put("name", user.getName());
                            r.put("email", user.getEmail());
                            r.put("mobile", user.getPhone());
                            r.put("role", user.getRole());
                            r.put("createdAt", user.getCreatedAt());
                            r.put("telegramLinked", user.getTelegramChatId() != null && !user.getTelegramChatId().isBlank());
                            r.put("brokerAccounts", accounts);
                            return r;
                        }));
    }

    public Mono<Map<String, Object>> updateMeProfile(UUID userId, Map<String, Object> body) {
        UpdateProfileRequest req = new UpdateProfileRequest();
        if (body.containsKey("displayName")) req.setName(String.valueOf(body.get("displayName")));
        if (body.containsKey("name")) req.setName(String.valueOf(body.get("name")));
        if (body.containsKey("telegramChatId")) req.setTelegramChatId(String.valueOf(body.get("telegramChatId")));
        return authService.updateProfile(userId, req)
                .then(getMeProfile(userId));
    }
}
