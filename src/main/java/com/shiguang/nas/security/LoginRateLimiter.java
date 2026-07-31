package com.shiguang.nas.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录限流。自实现而不是引 Bucket4j —— 单机单节点场景下核心逻辑就几十行，
 * 不值得为此扩大依赖面。
 *
 * <p>按 <b>IP</b> 和 <b>用户名</b> 两个维度独立计数，两个都必须没被限制才放行：
 * <ul>
 *   <li>只按用户名限：攻击者用一个字典打一万个账号，每个账号都没超限
 *   <li>只按 IP 限：局域网里换台设备（或伪造 X-Forwarded-For）就绕过
 * </ul>
 *
 * <p>惩罚是指数退避：连续失败 N 次后锁 2^(N-阈值) 分钟，上限 30 分钟。
 * 相比固定锁定，指数退避对正常用户手滑更宽容，对脚本更致命。
 */
@Component
public class LoginRateLimiter {

    /** 从第几次失败开始施加惩罚 */
    private static final int FREE_ATTEMPTS = 3;
    /** 单次锁定上限 */
    private static final Duration MAX_PENALTY = Duration.ofMinutes(30);
    /** 多久没有新的失败就把计数清零 */
    private static final Duration DECAY = Duration.ofHours(1);

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private static final class Attempt {
        final AtomicInteger count = new AtomicInteger();
        volatile long lastFailureAt;
        volatile long blockedUntil;
    }

    /** 返回还需等待的毫秒数；0 表示可以尝试登录。 */
    public long retryAfterMillis(String ip, String username) {
        long now = System.currentTimeMillis();
        return Math.max(blockedFor(key("ip", ip), now), blockedFor(key("user", username), now));
    }

    public void recordFailure(String ip, String username) {
        long now = System.currentTimeMillis();
        penalize(key("ip", ip), now);
        penalize(key("user", username), now);
    }

    public void recordSuccess(String ip, String username) {
        attempts.remove(key("ip", ip));
        attempts.remove(key("user", username));
    }

    private long blockedFor(String key, long now) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return 0;
        }
        if (now - attempt.lastFailureAt > DECAY.toMillis()) {
            attempts.remove(key);
            return 0;
        }
        return Math.max(0, attempt.blockedUntil - now);
    }

    private void penalize(String key, long now) {
        Attempt attempt = attempts.computeIfAbsent(key, k -> new Attempt());
        if (now - attempt.lastFailureAt > DECAY.toMillis()) {
            attempt.count.set(0);
        }
        attempt.lastFailureAt = now;
        int failures = attempt.count.incrementAndGet();
        if (failures > FREE_ATTEMPTS) {
            long penaltyMinutes = Math.min(
                    MAX_PENALTY.toMinutes(),
                    1L << Math.min(30, failures - FREE_ATTEMPTS - 1));
            attempt.blockedUntil = now + Duration.ofMinutes(penaltyMinutes).toMillis();
        }
    }

    private static String key(String scope, String value) {
        return scope + ':' + (value == null ? "" : value.toLowerCase(java.util.Locale.ROOT));
    }

    /** 定期清理已衰减的条目，防止被大量伪造用户名撑爆内存。 */
    public void evictExpired() {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> now - e.getValue().lastFailureAt > DECAY.toMillis());
    }

    int trackedKeys() {
        return attempts.size();
    }
}
