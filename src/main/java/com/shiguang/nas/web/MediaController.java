package com.shiguang.nas.web;

import com.shiguang.nas.common.ApiException;
import com.shiguang.nas.common.AuditService;
import com.shiguang.nas.media.Media;
import com.shiguang.nas.media.MediaLinkService;
import com.shiguang.nas.media.MediaRepository;
import com.shiguang.nas.media.MediaStorage;
import com.shiguang.nas.security.AppPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 媒体列表与条目操作。文件内容本身走 {@link MediaContentController}。
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    /** 单页条数上限。客户端可以要更少，但不能要更多——否则一个请求就能拖垮响应。 */
    private static final int MAX_PAGE_SIZE = 120;
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 批量操作的条数上限，防止一个请求更新几十万行 */
    private static final int MAX_BATCH = 500;

    private final MediaRepository repository;
    private final MediaStorage storage;
    private final MediaLinkService links;
    private final AuditService auditService;

    public MediaController(MediaRepository repository, MediaStorage storage,
                           MediaLinkService links, AuditService auditService) {
        this.repository = repository;
        this.storage = storage;
        this.links = links;
        this.auditService = auditService;
    }

    // ── 列表 ────────────────────────────────────────────────────────────

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "all") String view,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(required = false) Integer limit) {
        AppPrincipal principal = currentPrincipal();
        int size = limit == null ? DEFAULT_PAGE_SIZE : Math.clamp(limit, 1, MAX_PAGE_SIZE);

        // 多取一条来判断"还有没有下一页"，比再发一次 COUNT 查询便宜
        List<Media> rows = repository.feed(principal.userId(), view, q, cursor, size + 1);
        boolean hasMore = rows.size() > size;
        List<Media> page = hasMore ? rows.subList(0, size) : rows;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.stream().map(m -> view(m, principal.userId())).toList());
        result.put("nextCursor", hasMore ? MediaRepository.encodeCursor(page.get(page.size() - 1)) : null);
        return result;
    }

    @GetMapping("/counts")
    public Map<String, Object> counts() {
        AppPrincipal principal = currentPrincipal();
        Map<String, Object> result = new LinkedHashMap<>(repository.counts(principal.userId()));
        result.put("chunkSize", com.shiguang.nas.media.UploadService.CHUNK_SIZE);
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        AppPrincipal principal = currentPrincipal();
        return view(requireOwned(id, principal.userId()), principal.userId());
    }

    // ── 批量操作 ────────────────────────────────────────────────────────

    public record IdsRequest(List<Long> ids, Boolean starred) {
    }

    @PostMapping("/star")
    public Map<String, Object> star(@RequestBody IdsRequest body) {
        AppPrincipal principal = currentPrincipal();
        List<Long> ids = sanitize(body);
        boolean starred = body.starred() == null || body.starred();
        int affected = repository.setStarred(principal.userId(), ids, starred);
        return Map.of("ok", true, "affected", affected);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody IdsRequest body, HttpServletRequest request) {
        AppPrincipal principal = currentPrincipal();
        List<Long> ids = sanitize(body);
        int affected = repository.softDelete(principal.userId(), ids);
        auditService.record(principal.userId(), principal.username(), AuditService.MEDIA_DELETED,
                AuditService.OUTCOME_SUCCESS, request, "移入回收站 " + affected + " 项");
        return Map.of("ok", true, "affected", affected);
    }

    @PostMapping("/restore")
    public Map<String, Object> restore(@RequestBody IdsRequest body, HttpServletRequest request) {
        AppPrincipal principal = currentPrincipal();
        List<Long> ids = sanitize(body);
        int affected = repository.restore(principal.userId(), ids);
        auditService.record(principal.userId(), principal.username(), AuditService.MEDIA_RESTORED,
                AuditService.OUTCOME_SUCCESS, request, "恢复 " + affected + " 项");
        return Map.of("ok", true, "affected", affected);
    }

    /**
     * 彻底删除。
     *
     * <p>先删文件再删记录：反过来的话，删记录成功、删文件失败会留下一个
     * 谁也找不到、谁也删不掉的孤儿文件，长期下来把磁盘吃满。
     */
    @PostMapping("/purge")
    public Map<String, Object> purge(@RequestBody IdsRequest body, HttpServletRequest request) {
        AppPrincipal principal = currentPrincipal();
        List<Long> ids = sanitize(body);

        List<Media> targets = repository.findAll(principal.userId(), ids).stream()
                // 只允许清理回收站里的东西，避免一个误调用直接抹掉在用的媒体
                .filter(Media::deleted)
                .toList();
        targets.forEach(storage::delete);

        int affected = repository.hardDelete(principal.userId(),
                targets.stream().map(Media::id).toList());
        auditService.record(principal.userId(), principal.username(), AuditService.TRASH_PURGED,
                AuditService.OUTCOME_SUCCESS, request, "彻底删除 " + affected + " 项");
        return Map.of("ok", true, "affected", affected);
    }

    // ── 视图 ────────────────────────────────────────────────────────────

    private Map<String, Object> view(Media media, long ownerId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", media.id());
        item.put("kind", media.kind());
        item.put("mime", media.mime());
        item.put("ext", media.ext());
        item.put("name", media.origName());
        item.put("size", media.sizeBytes());
        item.put("width", media.width());
        item.put("height", media.height());
        item.put("durationMs", media.durationMs());
        item.put("takenAt", media.effectiveTime());
        item.put("createdAt", media.createdAt());
        item.put("starred", media.starred());
        item.put("playable", media.playable());
        item.put("thumbState", media.thumbState());
        item.put("deletedAt", media.deletedAt());

        MediaLinkService.SignedLink raw = links.sign(media.id(), ownerId, MediaLinkService.PURPOSE_RAW);
        item.put("rawUrl", contentUrl(media.id(), MediaLinkService.PURPOSE_RAW, raw));

        if (Media.THUMB_READY.equals(media.thumbState())) {
            MediaLinkService.SignedLink thumb =
                    links.sign(media.id(), ownerId, MediaLinkService.PURPOSE_THUMB);
            item.put("thumbUrl", contentUrl(media.id(), MediaLinkService.PURPOSE_THUMB, thumb));
        } else {
            item.put("thumbUrl", null);
        }

        MediaLinkService.SignedLink download =
                links.sign(media.id(), ownerId, MediaLinkService.PURPOSE_DOWNLOAD);
        item.put("downloadUrl", contentUrl(media.id(), MediaLinkService.PURPOSE_DOWNLOAD, download));
        return item;
    }

    private static String contentUrl(long id, String purpose, MediaLinkService.SignedLink link) {
        return "/api/content/" + id + "/" + purpose
                + "?exp=" + link.expiresAt() + "&sig=" + link.signature();
    }

    private Media requireOwned(long id, long ownerId) {
        Media media = repository.find(id)
                // 别人的媒体一律报 404 而不是 403：403 等于确认"这个 id 存在"，
                // 攻击者可以据此遍历出别人有多少东西
                .filter(m -> m.ownerId() == ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "媒体不存在"));
        return media;
    }

    private static List<Long> sanitize(IdsRequest body) {
        if (body == null || body.ids() == null || body.ids().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "没有选中任何项目");
        }
        List<Long> ids = body.ids().stream().filter(java.util.Objects::nonNull).distinct().toList();
        // 过滤掉 null 之后可能一个都不剩。静默返回 affected=0 会让调用方以为
        // "操作成功但没匹配到"，而实际是请求本身就不合法。
        if (ids.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "没有选中任何项目");
        }
        if (ids.size() > MAX_BATCH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "一次最多操作 " + MAX_BATCH + " 项");
        }
        return ids;
    }

    private static AppPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return principal;
    }
}
