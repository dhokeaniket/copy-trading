package com.copytrading.auth.dto;

public class ResetPasswordRequest {
    /** Legacy link-based reset. */
    private String token;
    /** Email OTP reset (preferred with Gmail). */
    private String email;
    private String otp;
    private String newPassword;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getOtp() { return otp; }
    public void setOtp(String otp) { this.otp = otp; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}
