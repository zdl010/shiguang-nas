package com.shiguang.nas.user;

public record UserAccount(
        long id,
        String username,
        String displayName,
        String passwordHash,
        String role,
        String status,
        int failedCount,
        Long lockedUntil,
        long createdAt,
        Long lastLoginAt,
        boolean mustChangePassword) {

    /** 唯一管理员的用户名。系统自动创建，不可被普通注册占用。 */
    public static final String ADMIN_USERNAME = "admin";

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    public boolean isLocked(long now) {
        return lockedUntil != null && lockedUntil > now;
    }
}
