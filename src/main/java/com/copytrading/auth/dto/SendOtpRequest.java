package com.copytrading.auth.dto;

public class SendOtpRequest {
    private String phone;
    private String purpose; // "login", "register", "reset"

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPurpose() { return purpose != null ? purpose : "login"; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
}
