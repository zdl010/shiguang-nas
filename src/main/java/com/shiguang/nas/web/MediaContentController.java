package com.shiguang.nas.web;

import com.shiguang.nas.media.Media;
import com.shiguang.nas.media.MediaLinkService;
import com.shiguang.nas.media.MediaRepository;
import com.shiguang.nas.media.MediaStorage;
import com.shiguang.nas.media.MediaStreamer;
import com.shiguang.nas.media.ThumbnailService;
import com.shiguang.nas.security.AppPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMapping.*;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 文件内容下发：原图/原视频、缩略图、下载。
 *
 * <p>这些 URL 走 HMAC 签名而不是会话（见 {@link MediaLinkService} 里的理由），
 * 所以在 SecurityConfig 里是 permitAll —— <b>鉴权完全由本类的签名校验负责</b>。
 * 改这个类时务必记得：这里少一次校验，就是全站媒体被任意下载。
 */
@RestController
@RequestMapping("/api/content")
public class MediaContentController {

    private final MediaRepository repository;
    private final MediaStorage storage;
    private final MediaLinkService links;
    private final MediaStreamer streamer;
    private final ThumbnailService thumbnails;

    public MediaContentController(MediaRepository repository, MediaStorage storage,
                                  MediaLinkService links, MediaStreamer streamer,
                                  ThumbnailService thumbnails) {
        this.repository = repository;
        this.storage = storage;
        this.links = links;
        this.streamer = streamer;
        this.thumbnails = thumbnails;
    }

    @GetMapping("/{id}/{purpose}")
    public void content(@PathVariable long id,
                        @PathVariable String purpose,
                        @RequestParam(name = "exp", required = false) Long expiresAt,
                        @RequestParam(name = "sig", required = false) String signature,
                        HttpServletRequest request,
                        HttpServletResponse response) throws IOException {

        Optional<Media> found = repository.find(id);
        if (found.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Media media = found.get();

        if (!authorized(media, purpose, expiresAt, signature)) {
            // 统一 404：403 会告诉对方"这个 id 是存在的"
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        switch (purpose) {
            case MediaLinkService.PURPOSE_THUMB -> {
                Path thumb = thumbnails.thumbPath(media);
                streamer.stream(thumb, "image/jpeg", null, request, response);
            }
            case MediaLinkService.PURPOSE_DOWNLOAD ->
                    streamer.stream(storage.resolve(media), media.mime(),
                            media.origName(), request, response);
            case MediaLinkService.PURPOSE_RAW ->
                    streamer.stream(storage.resolve(media), media.mime(), null, request, response);
            default -> response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    /**
     * 两条放行路径，满足其一即可：
     * <ol>
     *   <li>带有效签名（用于 img/video 标签、分享链接、下载器）
     *   <li>当前会话就是该媒体的属主（用于同一页面内的直接请求）
     * </ol>
     */
    private boolean authorized(Media media, String purpose, Long expiresAt, String signature) {
        if (expiresAt != null && signature != null
                && links.verify(media.id(), media.ownerId(), purpose, expiresAt, signature)) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.getPrincipal() instanceof AppPrincipal principal
                && principal.userId() == media.ownerId();
    }
}
