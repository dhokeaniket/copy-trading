package com.copytrading.auth.dto;

public class VerifyOtpRequest {
    private String phone;
    private String otp;
    private String purpose;

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getOtp() { return otp; }
    public void setOtp(String otp) { this.otp = otp; }
    public String getPurpose() { return purpose != null ? purpose : "login"; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
}
