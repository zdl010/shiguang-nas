package com.shiguang.nas.session;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class UserSessionRepository {

    private static final String COLUMNS =
            "id, user_id, user_agent, ip, created_at, last_seen_at, expires_at, revoked_at";

    private final JdbcClient jdbc;

    public UserSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static UserSessionRecord map(ResultSet rs, int rowNum) throws SQLException {
        long revoked = rs.getLong("revoked_at");
        return new UserSessionRecord(
                rs.getString("id"),
                rs.getLong("user_id"),
                rs.getString("user_agent"),
                rs.getString("ip"),
                rs.getLong("created_at"),
                rs.getLong("last_seen_at"),
                rs.getLong("expires_at"),
                rs.wasNull() ? null : revoked);
    }

    /**
     * 记录一次登录。
     *
     * <p>token_hash 存的是会话 ID 的 SHA-256 而不是 ID 本身：数据库被拖走时，
     * 攻击者拿到的是哈希，反推不出可用的会话 Cookie。
     */
    public void insert(String id, long userId, String tokenHash, String userAgent,
                       String ip, long expiresAt) {
        long now = System.currentTimeMillis();
        jdbc.sql("""
                INSERT INTO user_sessions(id, user_id, token_hash, user_agent, ip,
                                          created_at, last_seen_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                  user_id = excluded.user_id, token_hash = excluded.token_hash,
                  user_agent = excluded.user_agent, ip = excluded.ip,
                  last_seen_at = excluded.last_seen_at, expires_at = excluded.expires_at,
                  revoked_at = NULL
                """)
                .params(id, userId, tokenHash, userAgent, ip, now, now, expiresAt)
                .update();
    }

    public List<UserSessionRecord> listActive(long userId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM user_sessions
                 WHERE user_id = ? AND revoked_at IS NULL AND expires_at > ?
                 ORDER BY last_seen_at DESC
                """)
                .params(userId, System.currentTimeMillis())
                .query(UserSessionRepository::map)
                .list();
    }

    public Optional<UserSessionRecord> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM user_sessions WHERE id = ?")
                .param(id)
                .query(UserSessionRepository::map)
                .optional();
    }

    public void touch(String id, long at) {
        jdbc.sql("UPDATE user_sessions SET last_seen_at = ? WHERE id = ?")
                .params(at, id)
                .update();
    }

    public int revoke(String id, long userId) {
        return jdbc.sql("""
                UPDATE user_sessions SET revoked_at = ?
                WHERE id = ? AND user_id = ? AND revoked_at IS NULL
                """)
                .params(System.currentTimeMillis(), id, userId)
                .update();
    }

    /** 除 keepId 之外该用户的全部会话都作废，用于"改密码后踢掉其他设备"。 */
    public int revokeAllExcept(long userId, String keepId) {
        return jdbc.sql("""
                UPDATE user_sessions SET revoked_at = ?
                WHERE user_id = ? AND id <> ? AND revoked_at IS NULL
                """)
                .params(System.currentTimeMillis(), userId, keepId)
                .update();
    }

    public int deleteExpired(long now) {
        return jdbc.sql("DELETE FROM user_sessions WHERE expires_at < ? OR revoked_at IS NOT NULL")
                .param(now)
                .update();
    }

    /**
     * 清空全部会话记录。
     *
     * <p>会话本体存在 Tomcat 的内存里，进程一重启就全没了。表里留着的行会变成
     * 永远不会消失的"幽灵设备"，所以启动时必须清空——否则设置页会一直显示
     * 一堆早就不存在的登录设备。
     */
    public int deleteAll() {
        return jdbc.sql("DELETE FROM user_sessions").update();
    }
}
