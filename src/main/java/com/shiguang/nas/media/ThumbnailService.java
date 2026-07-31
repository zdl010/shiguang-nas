package com.shiguang.nas.media;

import com.shiguang.nas.common.FileSecurity;
import com.shiguang.nas.config.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 缩略图与封面帧生成。
 *
 * <p>放在后台跑而不是上传时同步生成：一个 4K 视频抽帧要几百毫秒到几秒，
 * 同步做会让手机端的上传请求卡住甚至超时，用户以为传失败了又传一遍。
 * 上传只负责落盘和入库，缩略图由这里慢慢补。
 */
@Service
public class ThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);

    /** 缩略图长边像素。够 2x 屏的网格显示，再大就是白白占磁盘。 */
    private static final int THUMB_SIZE = 480;
    private static final int BATCH = 8;

    private final MediaRepository repository;
    private final MediaStorage storage;
    private final FfmpegRunner runner;
    private final MediaProbe probe;
    private final AppPaths paths;

    /**
     * 防止定时任务的两次触发同时跑。
     *
     * <p>@Scheduled 默认单线程，本来不会重入；但这里同时也被上传流程直接调用，
     * 两条路径叠加就可能并发。用一个开关挡住比依赖调度器的行为更稳。
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ThumbnailService(MediaRepository repository, MediaStorage storage,
                            FfmpegRunner runner, MediaProbe probe, AppPaths paths) {
        this.repository = repository;
        this.storage = storage;
        this.runner = runner;
        this.probe = probe;
        this.paths = paths;
    }

    /** 缩略图文件路径。按 id 分两级目录，避免单目录几万个文件。 */
    public Path thumbPath(Media media) {
        String name = media.id() + ".jpg";
        return paths.thumbDir()
                .resolve(String.format("%02d", media.id() % 100))
                .resolve(name);
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 15_000)
    public void processPending() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Media> pending = repository.findPendingThumbs(BATCH);
            for (Media media : pending) {
                generate(media);
            }
        } catch (Exception e) {
            // 后台任务抛异常会让调度器停掉这个任务，必须兜住
            log.warn("缩略图批处理出错: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** 触发一次立即处理，上传完成后调用，让用户尽快看到图。 */
    public void nudge() {
        processPending();
    }

    private void generate(Media media) {
        Path source = storage.resolve(media);
        if (!Files.isReadable(source)) {
            repository.updateThumbState(media.id(), Media.THUMB_FAILED, "源文件不存在");
            return;
        }

        // 先补元数据。上传时没做，因为那会让请求多等一次 ffprobe。
        MediaProbe.ProbeResult probed = null;
        try {
            probed = probe.probe(source);
            // 照片不写时长：ffprobe 对单帧图片会报出一帧的长度（约 0.04 秒），
            // 存下来会让网格里每张照片都挂一个「0:00」角标，看着跟视频一样
            Long duration = Media.KIND_PHOTO.equals(media.kind()) ? null : probed.durationMs();
            repository.updateProbe(media.id(), probed.width(), probed.height(),
                    duration, probed.takenAt());
        } catch (Exception e) {
            log.debug("探测 {} 失败: {}", media.id(), e.getMessage());
        }

        // 纯音频没有画面。有内嵌封面的话 ffmpeg 能抽出来，抽不到就标 NONE，
        // 前端据此画渐变占位图（原型里音频卡片本来就是渐变色块）。
        boolean audioWithoutCover = Media.KIND_AUDIO.equals(media.kind())
                && (probed == null || !probed.hasVideoStream());
        if (audioWithoutCover) {
            repository.updateThumbState(media.id(), Media.THUMB_NONE, null);
            return;
        }

        Path target = thumbPath(media);
        try {
            FileSecurity.createSecureDirectory(target.getParent());
        } catch (IOException e) {
            repository.updateThumbState(media.id(), Media.THUMB_FAILED, "无法创建缩略图目录");
            return;
        }

        try {
            FfmpegRunner.Result result = runner.ffmpeg(buildArgs(media, source, target));
            if (result.ok() && Files.size(target) > 0) {
                repository.updateThumbState(media.id(), Media.THUMB_READY, null);
            } else {
                Files.deleteIfExists(target);
                repository.updateThumbState(media.id(), Media.THUMB_FAILED,
                        truncate(result.output()));
                log.debug("生成缩略图失败 id={}：{}", media.id(), truncate(result.output()));
            }
        } catch (IOException e) {
            repository.updateThumbState(media.id(), Media.THUMB_FAILED, truncate(e.getMessage()));
        }
    }

    private String[] buildArgs(Media media, Path source, Path target) {
        // scale 的表达式含义：长边缩到 THUMB_SIZE，短边按比例；不放大小图（force_original_aspect_ratio
        // 只管缩，min(iw,THUMB) 保证小图保持原样，省得把 100px 的图糊成 480px）
        String scale = "scale='min(%d,iw)':'min(%d,ih)':force_original_aspect_ratio=decrease"
                .formatted(THUMB_SIZE, THUMB_SIZE);

        if (Media.KIND_VIDEO.equals(media.kind())) {
            // -ss 放在 -i 前面是关键：这样 ffmpeg 直接跳到关键帧再解码，
            // 放后面会从头解码到那个时间点，长视频要几十秒。
            long seekMs = media.durationMs() == null ? 1000 : Math.min(3000, media.durationMs() / 10);
            return new String[]{
                    "-ss", String.valueOf(seekMs / 1000.0),
                    "-i", source.toAbsolutePath().toString(),
                    "-frames:v", "1",
                    "-vf", scale,
                    "-q:v", "4",
                    "-y", target.toAbsolutePath().toString()};
        }
        return new String[]{
                "-i", source.toAbsolutePath().toString(),
                // 只取第一帧：动图和多页 HEIC 否则会输出一堆文件
                "-frames:v", "1",
                "-vf", scale,
                "-q:v", "4",
                "-y", target.toAbsolutePath().toString()};
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String single = value.replace('\n', ' ').strip();
        return single.length() <= 200 ? single : single.substring(0, 200);
    }
}
