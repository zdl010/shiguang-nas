package com.shiguang.nas.web;

import com.shiguang.nas.common.ApiException;
import com.shiguang.nas.common.AuditService;
import com.shiguang.nas.security.AppPrincipal;
import com.shiguang.nas.security.LoginRateLimiter;
import com.shiguang.nas.session.SessionRegistry;
import com.shiguang.nas.user.UserAccount;
import com.shiguang.nas.user.UserRepository;
import com.shiguang.nas.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * 登录失败一律返回这一句，<b>绝不区分"用户不存在"和"密码错误"</b>。
     * 区分开等于送给攻击者一个用户名枚举接口。
     */
    private static final String GENERIC_LOGIN_FAILURE = "用户名或密码错误";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;
    private final AuditService auditService;
    private final SecurityContextRepository contextRepository;
    private final SessionRegistry sessionRegistry;

    /**
     * 用户不存在时拿来"陪跑"的假哈希。
     *
     * <p>如果用户不存在就直接返回，响应会比"存在但密码错"快上 50-100ms（Argon2 的耗时），
     * 攻击者据此就能枚举出哪些用户名真实存在。这里对一个随机口令算一次真哈希，
     * 让两条路径的耗时落在同一量级。
     */
    private final String dummyHash;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          LoginRateLimiter rateLimiter,
                          AuditService auditService,
                          SecurityContextRepository contextRepository,
                          SessionRegistry sessionRegistry) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.contextRepository = contextRepository;
        this.sessionRegistry = sessionRegistry;

        byte[] filler = new byte[32];
        new SecureRandom().nextBytes(filler);
        this.dummyHash = passwordEncoder.encode(Base64.getEncoder().encodeToString(filler));
    }

    public record LoginRequest(String username, String password) {
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest body,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        String username = UserService.normalizeUsername(body == null ? null : body.username());
        String password = body == null ? null : body.password();
        String ip = AuditService.clientIp(request);

        long retryAfter = rateLimiter.retryAfterMillis(ip, username);
        if (retryAfter > 0) {
            auditService.record(null, username, AuditService.LOGIN_BLOCKED,
                    AuditService.OUTCOME_FAILURE, request,
                    "限流拦截，剩余 " + (retryAfter / 1000) + " 秒");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "尝试过于频繁，请 " + Math.max(1, retryAfter / 1000) + " 秒后重试");
        }

        Optional<UserAccount> found = username.isEmpty()
                ? Optional.empty()
                : userRepository.findByUsername(username);

        // 无论用户是否存在都走一次哈希比对，保持两条路径耗时相当
        String hashToCheck = found.map(UserAccount::passwordHash).orElse(dummyHash);
        boolean passwordMatches = password != null && passwordEncoder.matches(password, hashToCheck);

        if (found.isEmpty() || !passwordMatches) {
            rateLimiter.recordFailure(ip, username);
            found.ifPresent(user -> userRepository.recordLoginFailure(
                    user.id(), user.failedCount() + 1, null));
            auditService.record(found.map(UserAccount::id).orElse(null), username,
                    AuditService.LOGIN_FAILURE, AuditService.OUTCOME_FAILURE, request, null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, GENERIC_LOGIN_FAILURE);
        }

        UserAccount user = found.get();
        if (!user.isActive()) {
            auditService.record(user.id(), username, AuditService.LOGIN_FAILURE,
                    AuditService.OUTCOME_FAILURE, request, "账号已停用");
            // 同样用通用文案，不告诉对方"这个账号存在但被停用了"
            throw new ApiException(HttpStatus.UNAUTHORIZED, GENERIC_LOGIN_FAILURE);
        }

        establishSession(user, request, response);
        rateLimiter.recordSuccess(ip, username);
        userRepository.recordLoginSuccess(user.id());

        HttpSession session = request.getSession(false);
        if (session != null) {
            sessionRegistry.register(session, user.id(), request);
        }
        auditService.record(user.id(), username, AuditService.LOGIN_SUCCESS,
                AuditService.OUTCOME_SUCCESS, request, null);

        return ResponseEntity.ok(Map.of(
                "id", user.id(),
                "username", user.username(),
                "displayName", user.displayName(),
                "role", user.role(),
                "mustChangePassword", user.mustChangePassword()));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppPrincipal principal) {
            auditService.record(principal.userId(), principal.username(), AuditService.LOGOUT,
                    AuditService.OUTCOME_SUCCESS, request, null);
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        // mustChangePassword 现查而不是从会话里读：改完密码后前端会重新拉一次 /me，
        // 从会话里读会拿到改密前的旧值，用户就被永远卡在改密页上了
        boolean mustChange = userRepository.findById(principal.userId())
                .map(UserAccount::mustChangePassword)
                .orElse(false);
        return Map.of(
                "id", principal.userId(),
                "username", principal.username(),
                "displayName", principal.displayName(),
                "role", principal.role(),
                "mustChangePassword", mustChange);
    }

    /**
     * 建立已认证会话。
     *
     * <p>先作废旧会话再建新的，这就是会话固定攻击的防护：攻击者预先塞给受害者一个
     * 已知的 JSESSIONID，受害者登录后该 ID 立即失效，攻击者手里的会话变成废纸。
     */
    private void establishSession(UserAccount user, HttpServletRequest request,
                                  HttpServletResponse response) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        request.getSession(true);

        AppPrincipal principal = new AppPrincipal(
                user.id(), user.username(), user.displayName(), user.role());
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}
