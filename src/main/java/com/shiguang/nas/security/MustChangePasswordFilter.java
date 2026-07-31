package com.shiguang.nas.security;

import com.shiguang.nas.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 强制改密网关。
 *
 * <p>管理员还在用初始密码时，除了下面这几个接口，其余 {@code /api/**} 一律 403。
 *
 * <p>为什么必须在后端拦而不是只在前端跳转：初始密码是公开的，任何人都能登进来。
 * 如果只靠前端引导，闯进来的人绕过页面直接调接口就能翻照片、发邀请码、改设置。
 * 这个过滤器才是那个窗口期里真正起作用的东西。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 200)
public class MustChangePasswordFilter extends OncePerRequestFilter {

    /**
     * 白名单。每一条都有必须放行的理由：
     * <ul>
     *   <li>{@code /api/account/password} —— 不放行就永远改不了密码，直接死锁
     *   <li>{@code /api/auth/me} —— 前端要靠它知道"该跳到改密页"
     *   <li>{@code /api/auth/logout} —— 总得允许人退出去
     *   <li>{@code /api/system/info} —— 未登录也能访问，本来就不含敏感信息
     * </ul>
     */
    private static final Set<String> ALLOWED = Set.of(
            "/api/account/password",
            "/api/auth/me",
            "/api/auth/logout",
            "/api/auth/login",
            "/api/system/info");

    private final UserRepository userRepository;

    public MustChangePasswordFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || ALLOWED.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppPrincipal principal
                && userRepository.findById(principal.userId())
                        .map(user -> user.mustChangePassword())
                        .orElse(false)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"error\":\"请先修改初始密码\",\"mustChangePassword\":true}");
            return;
        }
        chain.doFilter(request, response);
    }
}
