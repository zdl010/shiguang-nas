package com.shiguang.nas.web;

import com.shiguang.nas.common.ApiException;
import com.shiguang.nas.common.AuditService;
import com.shiguang.nas.config.SettingsService;
import com.shiguang.nas.security.AppPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 管理员专属接口：站点设置。
 *
 * <p>整个类挂 {@code @PreAuthorize("hasRole('ADMIN')")}。这比在每个方法里
 * 手写判断可靠——漏写一个方法就是一个越权漏洞，而类级别的注解不会漏。
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final SettingsService settings;
    private final AuditService auditService;

    public AdminController(SettingsService settings, AuditService auditService) {
        this.settings = settings;
        this.auditService = auditService;
    }

    // ── 设置 ────────────────────────────────────────────────────────────

    @GetMapping("/settings")
    public Map<String, Object> readSettings() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("trashRetentionDays",
                settings.getLong(SettingsService.TRASH_RETENTION_DAYS, 30));
        return view;
    }

    public record SettingsRequest(Integer trashRetentionDays) {
    }

    @PostMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody SettingsRequest body,
                                              HttpServletRequest request) {
        AppPrincipal principal = currentPrincipal();
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请求体为空");
        }
        if (body.trashRetentionDays() != null) {
            int days = body.trashRetentionDays();
            if (days < 1 || days > 365) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "回收站保留天数需在 1-365 之间");
            }
            settings.put(SettingsService.TRASH_RETENTION_DAYS, Integer.toString(days));
        }
        auditService.record(principal.userId(), principal.username(),
                AuditService.PROFILE_UPDATED, AuditService.OUTCOME_SUCCESS, request, "修改站点设置");
        return readSettings();
    }

    private static AppPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return principal;
    }
}
