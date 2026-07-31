package com.shiguang.nas.web;

import com.shiguang.nas.common.ApiException;
import com.shiguang.nas.common.AuditService;
import com.shiguang.nas.security.AppPrincipal;
import com.shiguang.nas.session.SessionRegistry;
import com.shiguang.nas.session.UserSessionRecord;
import com.shiguang.nas.user.UserAccount;
import com.shiguang.nas.user.UserRepository;
import com.shiguang.nas.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前登录用户的自助管理：改密码、改显示名、两步验证、已登录设备。
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final UserRepository userRepository;
    private final UserService userService;
    private final SessionRegistry sessionRegistry;
    private final AuditService auditService;

    public AccountController(UserRepository userRepository, UserService userService,
                             SessionRegistry sessionRegistry,
                             AuditService auditService) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.sessionRegistry = sessionRegistry;
        this.auditService = auditService;
    }

    // ── 资料 ────────────────────────────────────────────────────────────

    public record ProfileRequest(String displayName) {
    }

    @PostMapping("/profile")
    public Map<String, Object> updateProfile(@RequestBody ProfileRequest body,
                                             HttpServletRequest request) {
        AppPrincipal principal = currentPrincipal();
        String label = userService.updateDisplayName(
                principal.userId(), body == null ? null : body.displayName());
        auditService.record(principal.userId(), principal.username(),
                AuditService.PROFILE_UPDATED, AuditService.OUTCOME_SUCCESS, request, null);
        return Map.of("ok", true, "displayName", label);
    }

    // ── 改密码 ──────────────────────────────────────────────────────────

    public record PasswordRequest(String currentPassword, String newPassword) {
    }

    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody PasswordRequest body,
                                              HttpServletRequest request) {
        AppPrincipal principal = currentPrincipal();
        UserAccount user = loadUser(principal.userId());
        String newPassword = body == null ? null : body.newPassword();

        if (user.mustChangePassword()) {
            // 首次强制改密不要求原密码：当前会话就是几秒前用那个初始密码建立的，
            // 再验一遍没有任何安全增益，只是让用户多打一次字。
            //
            // 这个豁免**只在 must_change_password 为真时成立**。平时改密必须验原密码，
            // 否则一次会话劫持就等于永久接管账号——那才是原密码校验真正防的东西。
            userService.resetPassword(user, newPassword);
        } else {
            userService.changePassword(user,
                    body == null ? null : body.currentPassword(), newPassword);
        }

        // 改密码的常见动机就是"怀疑号被别人登着"，所以顺手把其他设备全踢掉
        HttpSession session = request.getSession(false);
        int revoked = session == null ? 0
                : sessionRegistry.revokeOthers(principal.userId(), session.getId());

        auditService.record(principal.userId(), principal.username(),
                AuditService.PASSWORD_CHANGED, AuditService.OUTCOME_SUCCESS, request,
                "同时下线了 " + revoked + " 台其他设备");
        return Map.of("ok", true, "revokedSessions", revoked);
    }

    // ── 已登录设备 ──────────────────────────────────────────────────────

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions(HttpServletRequest request) {
        AppPrincipal principal = currentPrincipal();
        HttpSession session = request.getSession(false);
        String currentId = session == null ? "" : session.getId();

        return sessionRegistry.listActive(principal.userId()).stream()
                .map(record -> sessionView(record, currentId))
                .toList();
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, Object> revokeSession(@PathVariable String id, HttpServletRequest request) {
        AppPrincipal principal = currentPrincipal();
        HttpSession session = request.getSession(false);
        if (session != null && session.getId().equals(id)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不能踢掉当前这台设备，请直接退出登录");
        }
        if (!sessionRegistry.revoke(id, principal.userId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "该设备不存在或已下线");
        }
        auditService.record(principal.userId(), principal.username(),
                AuditService.SESSION_REVOKED, AuditService.OUTCOME_SUCCESS, request, null);
        return Map.of("ok", true);
    }

    private Map<String, Object> sessionView(UserSessionRecord record, String currentId) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", record.id());
        view.put("current", record.id().equals(currentId));
        view.put("ip", record.ip());
        view.put("device", describeDevice(record.userAgent()));
        view.put("createdAt", record.createdAt());
        view.put("lastSeenAt", record.lastSeenAt());
        return view;
    }

    /**
     * 把 User-Agent 压成一句人话。
     *
     * <p>不原样回显 UA：那串东西又长又没法读，而且是攻击者可控的输入，
     * 直接塞进前端等于凭空开一个 XSS 面（虽然 Vue 会转义，但没必要冒这个险）。
     */
    private static String describeDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "未知设备";
        }
        String ua = userAgent.toLowerCase(java.util.Locale.ROOT);
        String os = ua.contains("iphone") ? "iPhone"
                : ua.contains("ipad") ? "iPad"
                : ua.contains("android") ? "Android"
                : ua.contains("mac os") || ua.contains("macintosh") ? "Mac"
                : ua.contains("windows") ? "Windows"
                : ua.contains("linux") ? "Linux"
                : "未知系统";
        String browser = ua.contains("edg/") ? "Edge"
                : ua.contains("chrome/") && !ua.contains("chromium") ? "Chrome"
                : ua.contains("firefox/") ? "Firefox"
                : ua.contains("safari/") ? "Safari"
                : "浏览器";
        return os + " · " + browser;
    }


    private UserAccount loadUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "账号不存在"));
    }

    private static AppPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return principal;
    }
}
