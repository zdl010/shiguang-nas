package com.shiguang.nas.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
// AdminController 靠 @PreAuthorize 做管理员校验，不开这个注解等于那些接口全部裸奔
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 内容安全策略。
     *
     * <p>{@code default-src 'self'} 是隐私底线：这台机器上的照片绝不能因为一个被注入的
     * 脚本标签就被外发到公网。所有资源都自托管，前端**不允许**引用任何 CDN
     * （原型里的 Google Fonts 已在正式前端中移除，改为本地字体）。
     *
     * <p>{@code blob:} 是必需的：上传前的本地预览和视频分片播放都依赖 blob URL。
     * {@code style-src 'unsafe-inline'} 是对 Vue 内联样式的妥协，脚本侧没有放开。
     */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "media-src 'self' blob:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "worker-src 'self' blob:",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    /**
     * bcrypt，代价因子 12（2^12 = 4096 轮）。
     *
     * <p>本来用的是 Argon2id（64 MiB 内存硬化，抗 GPU 强得多），换掉是因为
     * Spring Security 的 Argon2PasswordEncoder 硬依赖 BouncyCastle，
     * 而那个 jar 有 9.8 MB。scrypt 同样依赖 BC，所以 JDK/Spring 自带的选项里
     * bcrypt 是最强的一个（OWASP 排序：Argon2id &gt; scrypt &gt; bcrypt &gt; PBKDF2）。
     *
     * <p>代价因子取 12 而不是默认的 10：单次验证约 250ms，交互式登录无感，
     * 但把离线爆破的速度又压下去四倍，算是对失去内存硬化的一点补偿。
     *
     * <p><b>注意 bcrypt 只吃前 72 字节</b>，超出部分被静默丢弃。所以
     * {@code UserService} 的密码长度上限是按 UTF-8 字节数卡在 72 的——
     * 否则用户设一个长密码，实际生效的只有开头一截，而他毫不知情。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository contextRepository)
            throws Exception {

        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Strict").path("/"));

        http
                .authorizeHttpRequests(auth -> auth
                        // 未登录也必须能访问的最小集合
                        .requestMatchers("/api/system/info").permitAll()
                        .requestMatchers("/api/setup/**").permitAll()
                        .requestMatchers("/api/auth/login").permitAll()
                        // 媒体内容走 HMAC 签名而不是会话，鉴权在 MediaContentController 里做。
                        // 见那个类的注释——这里放行不等于不校验。
                        .requestMatchers("/api/content/**").permitAll()
                        // 前端静态资源与 SPA 路由
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/favicon.png",
                                "/assets/**", "/fonts/**", "/icons/**").permitAll()
                        // 其余接口一律需要登录，宁可误伤也不漏
                        .requestMatchers("/api/**").authenticated()
                        // 非 /api 的路径交给 SPA 转发控制器
                        .anyRequest().permitAll())

                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                // CSRF token 在 Spring Security 6+ 是延迟加载的，没人读就不下发 Cookie。
                // SPA 拿不到 token 会导致所有 POST 403，必须主动触发一次。详见该类注释。
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // 登录后换新会话 ID，防会话固定攻击
                        .sessionFixation(fixation -> fixation.newSession()))

                .headers(headers -> headers
                        // 默认是"等响应提交时再写"。绝大多数路径没问题，但首页 `/` 走的是
                        // Spring Boot 的欢迎页转发，HEAD 请求下这些头会整组丢失。
                        // 提前写死，安全头就不再依赖响应在哪一刻提交。
                        .withObjectPostProcessor(new ObjectPostProcessor<HeaderWriterFilter>() {
                            @Override
                            public <O extends HeaderWriterFilter> O postProcess(O filter) {
                                filter.setShouldWriteHeadersEagerly(true);
                                return filter;
                            }
                        })
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions
                                .policy("geolocation=(), camera=(), microphone=(), usb=(), payment=()"))
                        .httpStrictTransportSecurity(hsts -> hsts.disable()))

                // 未登录访问受保护接口时返回 401 JSON，而不是重定向到登录页。
                // SPA 需要的是状态码，重定向会让 fetch 拿到一个 200 的 HTML。
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.getWriter().write("{\"error\":\"未登录\"}");
                        })
                        .accessDeniedHandler((request, response, deniedException) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.getWriter().write("{\"error\":\"没有权限\"}");
                        }))

                .securityContext(context -> context.securityContextRepository(contextRepository))

                // 登录走自己的 JSON 接口（要串限流和审计），关掉框架自带的两种入口
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }
}
