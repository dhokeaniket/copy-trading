package com.copytrading.auth;

/** 2FA delivery channel: email (Gmail SMTP) or phone (Twilio SMS). */
public final class TwoFactorChannel {

    public static final String EMAIL = "EMAIL";
    public static final String PHONE = "PHONE";
    private static final String PENDING_PREFIX = "PENDING_";

    private TwoFactorChannel() {}

    public static String normalize(String channel) {
        if (channel == null || channel.isBlank()) return null;
        String c = channel.trim().toUpperCase();
        if (EMAIL.equals(c) || PHONE.equals(c)) return c;
        return null;
    }

    public static String pendingMarker(String channel) {
        return PENDING_PREFIX + normalize(channel);
    }

    public static boolean isPending(String secret) {
        return secret != null && secret.startsWith(PENDING_PREFIX);
    }

    public static String channelFromPending(String secret) {
        if (!isPending(secret)) return null;
        return secret.substring(PENDING_PREFIX.length());
    }
}
