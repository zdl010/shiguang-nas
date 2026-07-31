package com.shiguang.nas.web;

import com.shiguang.nas.common.ApiException;
import com.shiguang.nas.common.AuditService;
import com.shiguang.nas.config.AppPaths;
import com.shiguang.nas.media.MediaRepository;
import com.shiguang.nas.security.AppPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存储根目录的查看与修改（需求第 7 条的延伸）。
 */
@RestController
@RequestMapping("/api/admin/storage")
@PreAuthorize("hasRole('ADMIN')")
public class AdminStorageController {

    private final AppPaths appPaths;
    private final MediaRepository mediaRepository;
    private final AuditService auditService;

    public AdminStorageController(AppPaths appPaths, MediaRepository mediaRepository,
                                  AuditService auditService) {
        this.appPaths = appPaths;
        this.mediaRepository = mediaRepository;
        this.auditService = auditService;
    }

    @GetMapping
    public Map<String, Object> info() {
        AppPrincipal principal = currentPrincipal();
        Path root = appPaths.storageRoot();

        Path configured = appPaths.configuredStorageRoot();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", root.toAbsolutePath().toString());
        // 改过但还没重启时，这两个会不一样。界面据此提示"重启后生效"。
        result.put("configuredPath", configured.toAbsolutePath().toString());
        result.put("restartPending", !configured.toAbsolutePath().equals(root.toAbsolutePath()));
        result.put("writable", Files.isWritable(root));

        // 整块盘的容量，不是本应用占用的量——用户关心的是"还能存多少"
        try {
            FileStore store = Files.getFileStore(root);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            result.put("diskTotal", total);
            result.put("diskFree", usable);
            result.put("diskUsed", total - usable);
        } catch (IOException e) {
            result.put("diskTotal", 0L);
            result.put("diskFree", 0L);
            result.put("diskUsed", 0L);
        }

        var counts = mediaRepository.counts(principal.userId());
        result.put("mediaDir", appPaths.mediaDir().getFileName().toString());
        result.put("thumbDir", appPaths.thumbDir().getFileName().toString());
        result.put("tempDir", appPaths.tempDir().getFileName().toString());
        result.put("dbDir", appPaths.databaseDir().getFileName().toString());
        result.put("logDir", appPaths.logDir().getFileName().toString());
        result.put("counts", counts);
        result.put("configDir", appPaths.configDir().toAbsolutePath().toString());
        return result;
    }

    public record ChangeRequest(String path) {
    }

    @PostMapping
    public Map<String, Object> change(@RequestBody ChangeRequest body, HttpServletRequest request) {
        AppPrincipal principal = currentPrincipal();
        if (body == null || body.path() == null || body.path().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "路径不能为空");
        }
        try {
            Path updated = appPaths.updateStorageRoot(Path.of(body.path().strip()));
            auditService.record(principal.userId(), principal.username(),
                    AuditService.STORAGE_ROOT_CHANGED, AuditService.OUTCOME_SUCCESS,
                    request, "改为 " + updated);
            return Map.of(
                    "ok", true,
                    "path", updated.toString(),
                    "restartRequired", true,
                    // 明确告诉前端要提示什么，别让用户以为文件已经自己搬过去了
                    "message", "已保存。重启后生效——旧目录里的文件需要你自己搬到新目录。");
        } catch (java.nio.file.InvalidPathException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "路径格式不正确");
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "无法使用这个目录：" + e.getMessage());
        }
    }

    private static AppPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return principal;
    }
}
