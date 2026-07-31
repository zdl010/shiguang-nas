package com.shiguang.nas.web;

import com.shiguang.nas.common.ApiException;
import com.shiguang.nas.common.AuditService;
import com.shiguang.nas.security.AppPrincipal;
import com.shiguang.nas.user.UserAccount;
import com.shiguang.nas.user.UserRepository;
import com.shiguang.nas.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * 管理员的用户管理：列表、新增、重置密码、启用/停用。
 *
 * <p>刻意<b>没有"删除用户"</b>：用户底下挂着媒体文件，删了之后那些文件会变成
 * 谁也访问不到、谁也清理不掉的孤儿。停用足以达到"这个人不能再登录"的目的，
 * 而且是可逆的。真要腾空间，先让本人或管理员把媒体清掉再说。
 *
 * <p>也<b>不能新建管理员</b>：{@code admin} 是开机自动创建的唯一管理员。
 * 多一个管理员就多一个能改所有人密码、翻所有设置的账号，而这个产品的规模
 * （一个家庭）根本用不上第二个。
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserRepository userRepository;
    private final UserService userService;
    private final AuditService auditService;

    public AdminUserController(UserRepository userRepository, UserService userService,
                               AuditService auditService) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.auditService = auditService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return userRepository.listAll().stream().map(AdminUserController::view).toList();
    }

    public record CreateRequest(String username, String displayName, String password) {
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        AppPrincipal admin = currentPrincipal();
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请求体为空");
        }
        // 只能建普通用户。admin 是系统开机自动创建的唯一管理员，
        // 多一个管理员就多一个能改所有人密码、看所有设置的账号。
        UserAccount created = userService.createUser(
                body.username(), body.displayName(), body.password());

        auditService.record(admin.userId(), admin.username(), AuditService.USER_CREATED,
                AuditService.OUTCOME_SUCCESS, request, "新建用户 " + created.username());
        return view(created);
    }

    public record PasswordRequest(String newPassword) {
    }

    /**
     * 重置密码。
     *
     * <p>不要求提供对方的旧密码——管理员本来就没有。这也是为什么这个接口
     * 必须挂在 ADMIN 之下：任何人能调它就等于任何人能接管所有账号。
     */
    @PostMapping("/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable long id,
                                             @RequestBody PasswordRequest body,
                                             HttpServletRequest request) {
        AppPrincipal admin = currentPrincipal();
        UserAccount target = require(id);
        userService.resetPassword(target, body == null ? null : body.newPassword());

        auditService.record(admin.userId(), admin.username(), AuditService.PASSWORD_RESET,
                AuditService.OUTCOME_SUCCESS, request, "重置了 " + target.username() + " 的密码");
        return Map.of("ok", true);
    }

    public record StatusRequest(Boolean active) {
    }

    @PostMapping("/{id}/status")
    public Map<String, Object> setStatus(@PathVariable long id,
                                         @RequestBody StatusRequest body,
                                         HttpServletRequest request) {
        AppPrincipal admin = currentPrincipal();
        UserAccount target = require(id);
        boolean active = body == null || body.active() == null || body.active();

        // 把自己停用会立刻把自己锁在门外，而且没人能救——挡住
        if (!active && target.id() == admin.userId()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不能停用自己");
        }
        // admin 是唯一管理员，停用它等于把整个系统锁死，没有任何人能再管理
        if (!active && target.isAdmin()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "admin 是唯一管理员，不能停用");
        }

        userRepository.updateStatus(id, active ? UserAccount.STATUS_ACTIVE : UserAccount.STATUS_DISABLED);
        auditService.record(admin.userId(), admin.username(), AuditService.USER_STATUS_CHANGED,
                AuditService.OUTCOME_SUCCESS, request,
                (active ? "启用" : "停用") + " " + target.username());
        return Map.of("ok", true, "active", active);
    }

    private UserAccount require(long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在"));
    }

    /** 视图里<b>绝不能</b>出现 password_hash 和 totp_secret。 */
    private static Map<String, Object> view(UserAccount user) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", user.id());
        item.put("username", user.username());
        item.put("displayName", user.displayName());
        item.put("role", user.role());
        item.put("active", user.isActive());
        item.put("createdAt", user.createdAt());
        item.put("lastLoginAt", user.lastLoginAt());
        return item;
    }

    private static AppPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return principal;
    }
}
