package com.copytrading.auth.dto;

import java.util.UUID;

public class LoginResponse {
    private String accessToken;
    private String refreshToken;
    private UserDto user;
    private boolean requires2FA;

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public UserDto getUser() { return user; }
    public void setUser(UserDto user) { this.user = user; }
    public boolean isRequires2FA() { return requires2FA; }
    public void setRequires2FA(boolean requires2FA) { this.requires2FA = requires2FA; }

    public static LoginResponse twoFactorRequired(UserDto user) {
        LoginResponse r = new LoginResponse();
        r.setRequires2FA(true);
        r.setUser(user);
        return r;
    }

    public static LoginResponse success(String accessToken, String refreshToken, UserDto user) {
        LoginResponse r = new LoginResponse();
        r.setAccessToken(accessToken);
        r.setRefreshToken(refreshToken);
        r.setUser(user);
        r.setRequires2FA(false);
        return r;
    }
}
