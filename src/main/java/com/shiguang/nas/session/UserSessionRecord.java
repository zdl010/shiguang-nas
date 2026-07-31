package com.shiguang.nas.session;

public record UserSessionRecord(
        String id,
        long userId,
        String userAgent,
        String ip,
        long createdAt,
        long lastSeenAt,
        long expiresAt,
        Long revokedAt) {

    public boolean active(long now) {
        return revokedAt == null && expiresAt > now;
    }
}
