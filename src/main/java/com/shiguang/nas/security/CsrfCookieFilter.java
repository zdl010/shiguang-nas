package com.shiguang.nas.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 让 CSRF token 真正落到 XSRF-TOKEN Cookie 上。
 *
 * <p>Spring Security 6 起 CSRF token 是<b>延迟加载</b>的：没有人读它，Cookie 就不会下发。
 * 这对服务端渲染没问题（模板里总会读一次），但对 SPA 是死结——前端拿不到 token，
 * 所有 POST 都会 403。这个过滤器主动读一次，把 Cookie 逼出来。
 *
 * <p>为什么不干脆关掉 CSRF：本项目用 Cookie 承载会话，浏览器会在跨站请求里自动带上它。
 * SameSite=Strict 已经挡掉绝大部分，但 CSRF token 是不依赖浏览器行为的第二道防线，
 * 在一个存私人照片的产品里没有理由省掉。
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            // 这一次 getToken() 就是全部意义所在：触发 CookieCsrfTokenRepository 写响应 Cookie
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
