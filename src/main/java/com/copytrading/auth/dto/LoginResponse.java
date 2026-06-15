package com.copytrading.auth.dto;

import com.copytrading.auth.TwoFactorChannel;

public class LoginResponse {
    private String accessToken;
    private String refreshToken;
    private UserDto user;
    private boolean requires2FA;
    private boolean requiresEmailOtp;
    /** EMAIL or PHONE — where the login OTP was sent. */
    private String twoFactorChannel;
    private String message;
    private Integer otpExpiresIn;
    private Integer otpRetryAfter;

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public UserDto getUser() { return user; }
    public void setUser(UserDto user) { this.user = user; }
    public boolean isRequires2FA() { return requires2FA; }
    public void setRequires2FA(boolean requires2FA) { this.requires2FA = requires2FA; }
    public boolean isRequiresEmailOtp() { return requiresEmailOtp; }
    public void setRequiresEmailOtp(boolean requiresEmailOtp) { this.requiresEmailOtp = requiresEmailOtp; }
    public String getTwoFactorChannel() { return twoFactorChannel; }
    public void setTwoFactorChannel(String twoFactorChannel) { this.twoFactorChannel = twoFactorChannel; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Integer getOtpExpiresIn() { return otpExpiresIn; }
    public void setOtpExpiresIn(Integer otpExpiresIn) { this.otpExpiresIn = otpExpiresIn; }
    public Integer getOtpRetryAfter() { return otpRetryAfter; }
    public void setOtpRetryAfter(Integer otpRetryAfter) { this.otpRetryAfter = otpRetryAfter; }

    public static LoginResponse success(String accessToken, String refreshToken, UserDto user) {
        LoginResponse r = new LoginResponse();
        r.setAccessToken(accessToken);
        r.setRefreshToken(refreshToken);
        r.setUser(user);
        r.setRequires2FA(false);
        r.setRequiresEmailOtp(false);
        return r;
    }

    public static LoginResponse otpRequired(UserDto user, String channel, int expiresIn, int retryAfter) {
        LoginResponse r = new LoginResponse();
        r.setUser(user);
        r.setRequires2FA(true);
        r.setTwoFactorChannel(channel);
        r.setRequiresEmailOtp(TwoFactorChannel.EMAIL.equals(channel));
        r.setOtpExpiresIn(expiresIn);
        r.setOtpRetryAfter(retryAfter);
        if (TwoFactorChannel.PHONE.equals(channel)) {
            r.setMessage("A verification code was sent to your phone. Enter the 6-digit code to complete login.");
        } else {
            r.setMessage("A verification code was sent to your email. Enter the 6-digit code to complete login.");
        }
        return r;
    }
}
