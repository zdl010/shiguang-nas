package com.shiguang.nas.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    private static final String IP = "192.168.5.20";
    private static final String USER = "laoli";

    @Test
    void 前三次失败不惩罚第四次开始锁定() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(IP, USER);
            assertThat(limiter.retryAfterMillis(IP, USER))
                    .as("第 %d 次失败后仍应放行", i + 1)
                    .isZero();
        }

        limiter.recordFailure(IP, USER);
        assertThat(limiter.retryAfterMillis(IP, USER))
                .as("第 4 次失败后应被锁定")
                .isPositive();
    }

    /** 惩罚必须是指数增长的：固定时长的锁定对脚本几乎没有威慑 */
    @Test
    void 惩罚时长指数增长() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(IP, USER);
        }
        long first = limiter.retryAfterMillis(IP, USER);

        limiter.recordFailure(IP, USER);
        long second = limiter.retryAfterMillis(IP, USER);

        limiter.recordFailure(IP, USER);
        long third = limiter.retryAfterMillis(IP, USER);

        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
    }

    @Test
    void 惩罚不超过三十分钟上限() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 40; i++) {
            limiter.recordFailure(IP, USER);
        }
        assertThat(limiter.retryAfterMillis(IP, USER))
                .isLessThanOrEqualTo(30 * 60 * 1000L);
    }

    /**
     * 换个 IP 打同一个账号照样被拦：只按 IP 限流的话，
     * 局域网里换台设备就能接着爆破。
     */
    @Test
    void 用户名维度独立于IP生效() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("192.168.5." + i, USER);
        }
        assertThat(limiter.retryAfterMillis("10.0.0.99", USER)).isPositive();
    }

    /**
     * 同一个 IP 拿字典打不同账号也要被拦：只按用户名限流的话，
     * 每个账号都不超限，攻击者能无成本横扫全部账号。
     */
    @Test
    void IP维度独立于用户名生效() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(IP, "victim" + i);
        }
        assertThat(limiter.retryAfterMillis(IP, "someoneelse")).isPositive();
    }

    @Test
    void 用户名大小写不影响计数() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        limiter.recordFailure(IP, "LaoLi");
        limiter.recordFailure(IP, "laoli");
        limiter.recordFailure(IP, "LAOLI");
        limiter.recordFailure(IP, "lAoLi");

        assertThat(limiter.retryAfterMillis("10.0.0.1", "laoli")).isPositive();
    }

    @Test
    void 登录成功清空双维度计数() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(IP, USER);
        }
        assertThat(limiter.retryAfterMillis(IP, USER)).isPositive();

        limiter.recordSuccess(IP, USER);

        assertThat(limiter.retryAfterMillis(IP, USER)).isZero();
        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    void 空用户名不抛异常() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        limiter.recordFailure(IP, null);
        limiter.recordFailure(IP, "");

        assertThat(limiter.retryAfterMillis(IP, null)).isZero();
    }

    /** 攻击者可以伪造海量用户名，条目必须能被回收，否则是一条内存耗尽路径 */
    @Test
    void 清理过期条目() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 100; i++) {
            limiter.recordFailure("10.0.0." + i, "user" + i);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(200);

        // 刚刚发生的失败还没到衰减时间（1 小时），不该被清掉
        limiter.evictExpired();
        assertThat(limiter.trackedKeys()).isEqualTo(200);
    }
}
