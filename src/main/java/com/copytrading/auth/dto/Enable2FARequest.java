package com.copytrading.auth.dto;

public class Enable2FARequest {
    /** EMAIL or PHONE */
    private String channel;

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
}
