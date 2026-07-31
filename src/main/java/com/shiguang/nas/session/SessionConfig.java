package com.shiguang.nas.session;

import com.shiguang.nas.security.AppPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration
public class SessionConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionConfig.class);

    /**
     * 把 SessionRegistry 注册成 Servlet 容器的会话监听器。
     *
     * <p>只加 {@code @Component} 是不够的——Spring 不会自动把 Bean 装进 Servlet
     * 容器的监听器链，必须显式注册，否则 sessionDestroyed 永远不触发，
     * 内存里的会话映射只增不减。
     */
    @Bean
    public ServletListenerRegistrationBean<SessionRegistry> sessionListener(SessionRegistry registry) {
        return new ServletListenerRegistrationBean<>(registry);
    }

    /**
     * 启动时清空会话表。
     *
     * <p>会话本体在 Tomcat 内存里，进程重启就没了。表里残留的行会变成永远不消失的
     * "幽灵设备"，让设置页显示一堆早已不存在的登录记录。
     */
    @Bean
    public org.springframework.boot.CommandLineRunner clearStaleSessions(
            UserSessionRepository repository) {
        return args -> {
            int removed = repository.deleteAll();
            if (removed > 0) {
                log.info("清理上次运行残留的 {} 条会话记录", removed);
            }
        };
    }

    /** 刷新已登录会话的 last_seen_at，让"在线设备"列表的时间是准的。 */
    @Component
    @Order(Ordered.LOWEST_PRECEDENCE - 100)
    public static class SessionTouchFilter extends OncePerRequestFilter {

        private final SessionRegistry registry;

        public SessionTouchFilter(SessionRegistry registry) {
            this.registry = registry;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            chain.doFilter(request, response);

            // 放在链后面执行：登录请求是在链中间才建立会话的，
            // 放前面会拿不到刚建出来的那个会话
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AppPrincipal) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    registry.touch(session.getId());
                }
            }
        }
    }
}
