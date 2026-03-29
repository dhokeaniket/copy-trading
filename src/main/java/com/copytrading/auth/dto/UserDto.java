package com.copytrading.auth.dto;

import com.copytrading.auth.UserAccount;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class UserDto {
    private UUID userId;
    private String name;
    private String email;
    private String role;
    private String status;
    private String phone;
    private boolean twoFactorEnabled;
    private Instant createdAt;
    private List<Object> brokerAccounts = List.of();

    public static UserDto from(UserAccount u) {
        UserDto d = new UserDto();
        d.userId = u.getId();
        d.name = u.getName();
        d.email = u.getEmail();
        d.role = u.getRole();
        d.status = u.getStatus();
        d.phone = u.getPhone();
        d.twoFactorEnabled = u.isTwoFactorEnabled();
        d.createdAt = u.getCreatedAt();
        d.brokerAccounts = List.of(); // populated when broker module is wired
        return d;
    }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public boolean isTwoFactorEnabled() { return twoFactorEnabled; }
    public void setTwoFactorEnabled(boolean twoFactorEnabled) { this.twoFactorEnabled = twoFactorEnabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<Object> getBrokerAccounts() { return brokerAccounts; }
    public void setBrokerAccounts(List<Object> brokerAccounts) { this.brokerAccounts = brokerAccounts; }
}
