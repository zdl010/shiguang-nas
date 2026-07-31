package com.shiguang.nas.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 安全审计日志。落库而不是只写文本日志，是为了能在设置页直接展示
 *"最近的登录记录 / 谁删了东西"，让机主自己就能发现异常。
 *
 * <p>纪律：<b>永远不要把密码、令牌、签名、完整文件路径写进 detail。</b>
 * 审计表本身也是攻击者的目标，它只该记录"谁在什么时候做了什么"。
 */
@Service
public class AuditService {

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String LOGIN_BLOCKED = "LOGIN_BLOCKED";
    public static final String LOGOUT = "LOGOUT";
    public static final String SETUP_ADMIN_CREATED = "SETUP_ADMIN_CREATED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PROFILE_UPDATED = "PROFILE_UPDATED";
    public static final String SESSION_REVOKED = "SESSION_REVOKED";
    public static final String MEDIA_UPLOADED = "MEDIA_UPLOADED";
    public static final String MEDIA_DELETED = "MEDIA_DELETED";
    public static final String MEDIA_RESTORED = "MEDIA_RESTORED";
    public static final String TRASH_PURGED = "TRASH_PURGED";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_STATUS_CHANGED = "USER_STATUS_CHANGED";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String STORAGE_ROOT_CHANGED = "STORAGE_ROOT_CHANGED";

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";

    private static final int MAX_UA_LENGTH = 256;
    private static final int MAX_DETAIL_LENGTH = 512;

    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Long userId, String username, String action, String outcome,
                       HttpServletRequest request, String detail) {
        jdbc.sql("""
                INSERT INTO audit_log(user_id, username, action, outcome, ip, user_agent,
                                      detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(userId,
                        username,
                        action,
                        outcome,
                        clientIp(request),
                        truncate(header(request, "User-Agent"), MAX_UA_LENGTH),
                        truncate(detail, MAX_DETAIL_LENGTH),
                        System.currentTimeMillis())
                .update();
    }

    /**
     * 取客户端 IP。
     *
     * <p><b>刻意不读 X-Forwarded-For。</b> 本产品直接监听局域网，前面没有反向代理，
     * 读取该头等于让攻击者自由伪造来源 IP，直接废掉按 IP 的限流和审计。
     * 将来若真要支持反代，必须配合可信代理白名单再启用。
     */
    public static String clientIp(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }

    private static String header(HttpServletRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
