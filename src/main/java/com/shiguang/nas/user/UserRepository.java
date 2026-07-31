package com.shiguang.nas.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserRepository {

    private static final String COLUMNS = """
            id, username, display_name, password_hash, role, status,
            failed_count, locked_until, created_at, last_login_at, must_change_password
            """;

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static UserAccount map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserAccount(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getInt("failed_count"),
                nullableLong(rs, "locked_until"),
                rs.getLong("created_at"),
                nullableLong(rs, "last_login_at"),
                rs.getInt("must_change_password") != 0);
    }

    /** wasNull() 只对紧邻的上一次 get 有效，必须逐列判断，不能攒到最后。 */
    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE username = ?")
                .param(username)
                .query(UserRepository::map)
                .optional();
    }

    public Optional<UserAccount> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = ?")
                .param(id)
                .query(UserRepository::map)
                .optional();
    }

    public boolean existsByUsername(String username) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM users WHERE username = ?")
                .param(username)
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    public boolean anyAdminExists() {
        Long count = jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'")
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    public long insert(String username, String displayName, String passwordHash, String role) {
        long now = System.currentTimeMillis();
        jdbc.sql("""
                INSERT INTO users(username, display_name, password_hash, role, status,
                                  failed_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0, ?, ?)
                """)
                .params(username, displayName, passwordHash, role, now, now)
                .update();
        Long id = jdbc.sql("SELECT id FROM users WHERE username = ?")
                .param(username)
                .query(Long.class)
                .single();
        return id == null ? -1 : id;
    }

    public void recordLoginSuccess(long userId) {
        long now = System.currentTimeMillis();
        jdbc.sql("""
                UPDATE users SET failed_count = 0, locked_until = NULL,
                                 last_login_at = ?, updated_at = ?
                WHERE id = ?
                """)
                .params(now, now, userId)
                .update();
    }

    /**
     * 改密码。<b>同时清掉强制改密标记</b>——两件事必须一起做，
     * 分开写迟早会出现"密码改了但还被网关拦着"的死锁状态。
     */
    public void updatePassword(long userId, String passwordHash) {
        jdbc.sql("UPDATE users SET password_hash = ?, must_change_password = 0, updated_at = ? WHERE id = ?")
                .params(passwordHash, System.currentTimeMillis(), userId)
                .update();
    }

    /** 建账号时用，绕开密码策略校验（初始密码本来就是要被立刻改掉的）。 */
    public long insertBootstrapAdmin(String username, String displayName, String passwordHash) {
        long now = System.currentTimeMillis();
        jdbc.sql("""
                INSERT INTO users(username, display_name, password_hash, role, status,
                                  failed_count, created_at, updated_at, must_change_password)
                VALUES (?, ?, ?, 'ADMIN', 'ACTIVE', 0, ?, ?, 1)
                """)
                .params(username, displayName, passwordHash, now, now)
                .update();
        Long id = jdbc.sql("SELECT id FROM users WHERE username = ?")
                .param(username)
                .query(Long.class)
                .single();
        return id == null ? -1 : id;
    }

    public void updateDisplayName(long userId, String displayName) {
        jdbc.sql("UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?")
                .params(displayName, System.currentTimeMillis(), userId)
                .update();
    }

    public java.util.List<UserAccount> listAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users ORDER BY id")
                .query(UserRepository::map)
                .list();
    }

    public void updateStatus(long userId, String status) {
        jdbc.sql("UPDATE users SET status = ?, updated_at = ? WHERE id = ?")
                .params(status, System.currentTimeMillis(), userId)
                .update();
    }

    /** 还能登录的管理员数量。停用最后一个管理员会让系统再也没人能管。 */
    public long countActiveAdmins() {
        Long count = jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE'")
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    public long countUsers() {
        Long count = jdbc.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
        return count == null ? 0 : count;
    }

    public void recordLoginFailure(long userId, int failedCount, Long lockedUntil) {
        jdbc.sql("UPDATE users SET failed_count = ?, locked_until = ?, updated_at = ? WHERE id = ?")
                .params(failedCount, lockedUntil, System.currentTimeMillis(), userId)
                .update();
    }
}
