package com.shiguang.nas.web;

import com.shiguang.nas.common.ApiException;
import com.shiguang.nas.common.AuditService;
import com.shiguang.nas.media.Media;
import com.shiguang.nas.media.UploadService;
import com.shiguang.nas.security.AppPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private final UploadService uploadService;
    private final AuditService auditService;

    public UploadController(UploadService uploadService, AuditService auditService) {
        this.uploadService = uploadService;
        this.auditService = auditService;
    }

    public record InitRequest(String sha256, String name, Long size) {
    }

    @PostMapping("/init")
    public Map<String, Object> init(@RequestBody InitRequest body) {
        AppPrincipal principal = currentPrincipal();
        if (body == null || body.size() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少文件信息");
        }
        UploadService.InitResult result = uploadService.init(
                principal.userId(), body.sha256(), body.name(), body.size());

        Map<String, Object> view = new LinkedHashMap<>();
        // 秒传：existingMediaId 有值时客户端直接跳过所有分片
        view.put("instant", result.existingMediaId() != null);
        view.put("mediaId", result.existingMediaId());
        view.put("uploadId", result.uploadId());
        view.put("chunkSize", result.chunkSize());
        view.put("chunkTotal", result.chunkTotal());
        view.put("receivedChunks", result.receivedChunks());
        return view;
    }

    @PostMapping("/chunk")
    public Map<String, Object> chunk(@RequestParam String uploadId,
                                     @RequestParam int index,
                                     @RequestParam("file") MultipartFile file) {
        AppPrincipal principal = currentPrincipal();
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "分片内容为空");
        }
        uploadService.acceptChunk(principal.userId(), uploadId, index, file);
        return Map.of("ok", true, "index", index);
    }

    public record CompleteRequest(String uploadId) {
    }

    @PostMapping("/complete")
    public Map<String, Object> complete(@RequestBody CompleteRequest body,
                                        HttpServletRequest request) {
        AppPrincipal principal = currentPrincipal();
        if (body == null || body.uploadId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少 uploadId");
        }
        Media media = uploadService.complete(principal.userId(), body.uploadId());
        auditService.record(principal.userId(), principal.username(), AuditService.MEDIA_UPLOADED,
                AuditService.OUTCOME_SUCCESS, request, media.kind() + " " + media.sizeBytes() + " 字节");
        return Map.of("ok", true, "mediaId", media.id());
    }

    @PostMapping("/abort")
    public Map<String, Object> abort(@RequestBody CompleteRequest body) {
        AppPrincipal principal = currentPrincipal();
        if (body == null || body.uploadId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少 uploadId");
        }
        uploadService.abort(principal.userId(), body.uploadId());
        return Map.of("ok", true);
    }

    private static AppPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return principal;
    }
}
