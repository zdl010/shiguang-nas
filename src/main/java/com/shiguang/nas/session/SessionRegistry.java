package com.shiguang.nas.session;

import com.shiguang.nas.common.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已登录设备的登记处，支撑"查看在线设备 / 强制下线"。
 *
 * <p>为什么不用 Spring Session：那个模块是为集群共享会话设计的，会把整个会话
 * 序列化进数据库，每个请求多两次 IO。本产品是单机单进程，会话本体留在 Tomcat 内存里
 * 就够了，这里只额外维护一份"谁在哪台设备登录着"的元数据用于展示和踢人。
 *
 * <p>内存里的 {@code live} 映射是"踢人"能真正生效的关键：数据库里标记 revoked
 * 只是让列表不再显示，要让对方的下一个请求立刻失效，必须拿到那个
 * {@link HttpSession} 对象调用 invalidate。
 */
@Component
public class SessionRegistry implements HttpSessionListener {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    /** last_seen_at 的写库节流：同一会话 60 秒内只更新一次 */
    private static final long TOUCH_INTERVAL_MS = 60_000;

    private final Map<String, HttpSession> live = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTouch = new ConcurrentHashMap<>();
    private final UserSessionRepository repository;

    public SessionRegistry(UserSessionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        live.put(event.getSession().getId(), event.getSession());
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        String id = event.getSession().getId();
        live.remove(id);
        lastTouch.remove(id);
    }

    /** 登录成功后调用，登记这台设备。 */
    public void register(HttpSession session, long userId, HttpServletRequest request) {
        String id = session.getId();
        live.put(id, session);
        long expiresAt = System.currentTimeMillis()
                + Math.max(1, session.getMaxInactiveInterval()) * 1000L;
        repository.insert(id, userId, sha256(id),
                request.getHeader("User-Agent"), AuditService.clientIp(request), expiresAt);
        lastTouch.put(id, System.currentTimeMillis());
    }

    /** 每个已认证请求调用一次，节流后更新 last_seen_at。 */
    public void touch(String sessionId) {
        long now = System.currentTimeMillis();
        Long previous = lastTouch.get(sessionId);
        if (previous != null && now - previous < TOUCH_INTERVAL_MS) {
            return;
        }
        lastTouch.put(sessionId, now);
        repository.touch(sessionId, now);
    }

    public List<UserSessionRecord> listActive(long userId) {
        return repository.listActive(userId);
    }

    /**
     * 强制下线。
     *
     * @return true 表示确实踢掉了一个属于该用户的会话
     */
    public boolean revoke(String sessionId, long userId) {
        // 先确认这个会话确实属于调用者，否则任何人都能凭猜到的 ID 踢掉别人
        boolean owned = repository.find(sessionId)
                .map(record -> record.userId() == userId)
                .orElse(false);
        if (!owned) {
            return false;
        }
        repository.revoke(sessionId, userId);
        invalidate(sessionId);
        return true;
    }

    /** 踢掉该用户除当前会话外的全部设备，改密码后调用。 */
    public int revokeOthers(long userId, String keepSessionId) {
        int count = repository.revokeAllExcept(userId, keepSessionId);
        repository.listActive(userId).stream()
                .map(UserSessionRecord::id)
                .filter(id -> !id.equals(keepSessionId))
                .forEach(this::invalidate);
        // listActive 已经过滤掉 revoked 的行，所以上面那轮通常是空的；
        // 真正生效的是下面这轮：直接扫内存里还活着的会话
        live.keySet().stream()
                .filter(id -> !id.equals(keepSessionId))
                .filter(id -> repository.find(id).map(r -> r.userId() == userId).orElse(false))
                .toList()
                .forEach(this::invalidate);
        return count;
    }

    private void invalidate(String sessionId) {
        HttpSession session = live.remove(sessionId);
        lastTouch.remove(sessionId);
        if (session == null) {
            return;
        }
        try {
            session.invalidate();
        } catch (IllegalStateException e) {
            // 对方自己已经登出了，会话已失效。这不是错误。
            log.debug("会话 {} 已经失效，跳过", sessionId);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
