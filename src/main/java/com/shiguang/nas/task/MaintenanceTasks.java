package com.shiguang.nas.task;

import com.shiguang.nas.config.SettingsService;
import com.shiguang.nas.media.Media;
import com.shiguang.nas.media.MediaRepository;
import com.shiguang.nas.media.MediaStorage;
import com.shiguang.nas.media.UploadService;
import com.shiguang.nas.security.LoginRateLimiter;
import com.shiguang.nas.session.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 后台清理。
 *
 * <p>每一项都是"不做也能跑，但跑久了会出问题"的：会话表会无限增长、
 * 限流表会被伪造用户名撑爆、传一半的分片会占满磁盘、回收站永远不清空。
 *
 * <p>所有任务都吞掉异常：{@code @Scheduled} 的方法抛出异常会让 Spring
 * 停止后续调度，一次偶发的 IO 错误就能让清理永久停摆。
 */
@Component
public class MaintenanceTasks {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceTasks.class);

    private final UserSessionRepository sessions;
    private final LoginRateLimiter rateLimiter;
    private final UploadService uploadService;
    private final MediaRepository mediaRepository;
    private final MediaStorage storage;
    private final SettingsService settings;

    public MaintenanceTasks(UserSessionRepository sessions, LoginRateLimiter rateLimiter,
                            UploadService uploadService, MediaRepository mediaRepository,
                            MediaStorage storage, SettingsService settings) {
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
        this.uploadService = uploadService;
        this.mediaRepository = mediaRepository;
        this.storage = storage;
        this.settings = settings;
    }

    /** 每小时：过期会话 + 限流表。 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void hourly() {
        try {
            int removed = sessions.deleteExpired(System.currentTimeMillis());
            rateLimiter.evictExpired();
            if (removed > 0) {
                log.info("清理过期会话 {} 条", removed);
            }
        } catch (Exception e) {
            log.warn("每小时清理任务出错: {}", e.getMessage());
        }
    }

    /** 每 6 小时：过期的上传会话与残留分片。 */
    @Scheduled(initialDelay = 300_000, fixedDelay = 6 * 3_600_000)
    public void purgeUploads() {
        try {
            int removed = uploadService.purgeExpired();
            if (removed > 0) {
                log.info("清理过期上传会话 {} 条", removed);
            }
        } catch (Exception e) {
            log.warn("清理上传会话出错: {}", e.getMessage());
        }
    }

    /**
     * 每天：回收站里超过保留期的条目彻底删除。
     *
     * <p>顺序是先删文件再删记录。反过来会留下找不到、也删不掉的孤儿文件。
     */
    @Scheduled(initialDelay = 600_000, fixedDelay = 24 * 3_600_000)
    public void purgeTrash() {
        try {
            long days = settings.getLong(SettingsService.TRASH_RETENTION_DAYS, 30);
            long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();

            List<Media> expired = mediaRepository.findExpiredTrash(cutoff);
            if (expired.isEmpty()) {
                return;
            }
            expired.forEach(storage::delete);

            // 按属主分组：hardDelete 的 SQL 里带 owner_id 条件，是防越权的第二道闸
            Map<Long, List<Long>> byOwner = expired.stream()
                    .collect(Collectors.groupingBy(Media::ownerId,
                            Collectors.mapping(Media::id, Collectors.toList())));
            int total = 0;
            for (var entry : byOwner.entrySet()) {
                total += mediaRepository.hardDelete(entry.getKey(), entry.getValue());
            }
            log.info("回收站超过 {} 天的 {} 项已彻底删除", days, total);
        } catch (Exception e) {
            log.warn("清理回收站出错: {}", e.getMessage());
        }
    }
}
