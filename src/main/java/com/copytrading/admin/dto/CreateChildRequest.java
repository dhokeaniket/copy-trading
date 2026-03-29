package com.copytrading.admin.dto;

import java.util.UUID;

public class CreateChildRequest {
    private String name;
    private String email;
    private String password;
    private String phone;
    private UUID assignedMasterId;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public UUID getAssignedMasterId() { return assignedMasterId; }
    public void setAssignedMasterId(UUID assignedMasterId) { this.assignedMasterId = assignedMasterId; }
}
