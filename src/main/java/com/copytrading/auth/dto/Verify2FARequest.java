package com.copytrading.auth.dto;

public class Verify2FARequest {
    private String otp;

    public String getOtp() { return otp; }
    public void setOtp(String otp) { this.otp = otp; }
}
