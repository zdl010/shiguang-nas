package com.shiguang.nas.media;

import com.shiguang.nas.common.FileSecurity;
import com.shiguang.nas.config.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 媒体文件在磁盘上的落位。
 *
 * <p>路径完全由服务端生成（{@code media/<owner>/<sha 前两位>/<sha>.<ext>}），
 * <b>绝不使用用户提供的文件名做路径</b>。原始文件名只存进数据库用于展示——
 * 用它拼路径就等于把目录穿越、非法字符、超长路径全放进来。
 */
@Service
public class MediaStorage {

    private static final Logger log = LoggerFactory.getLogger(MediaStorage.class);

    /**
     * 允许的扩展名白名单。
     *
     * <p>用白名单而不是黑名单：产品只接受图片、视频、音频，能列全。
     * 黑名单永远会漏（.jsp、.phtml、.svgz……），而这个目录将来可能被
     * 某个配置错误的服务当成静态资源目录暴露出去。
     */
    private static final Set<String> PHOTO_EXT = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "tiff", "tif");
    private static final Set<String> VIDEO_EXT = Set.of(
            "mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp", "hevc");
    private static final Set<String> AUDIO_EXT = Set.of(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "alac");

    private final AppPaths paths;

    public MediaStorage(AppPaths paths) {
        this.paths = paths;
    }

    public boolean supported(String ext) {
        String normalized = normalizeExt(ext);
        return PHOTO_EXT.contains(normalized)
                || VIDEO_EXT.contains(normalized)
                || AUDIO_EXT.contains(normalized);
    }

    public String kindOf(String ext) {
        String normalized = normalizeExt(ext);
        if (VIDEO_EXT.contains(normalized)) {
            return Media.KIND_VIDEO;
        }
        if (AUDIO_EXT.contains(normalized)) {
            return Media.KIND_AUDIO;
        }
        return Media.KIND_PHOTO;
    }

    public String mimeOf(String ext) {
        return switch (normalizeExt(ext)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "heic", "heif" -> "image/heic";
            case "avif" -> "image/avif";
            case "bmp" -> "image/bmp";
            case "tiff", "tif" -> "image/tiff";
            case "mp4", "m4v" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "webm" -> "video/webm";
            case "mkv" -> "video/x-matroska";
            case "avi" -> "video/x-msvideo";
            case "3gp" -> "video/3gpp";
            case "mp3" -> "audio/mpeg";
            case "m4a", "alac" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "wav" -> "audio/wav";
            case "ogg", "opus" -> "audio/ogg";
            default -> "application/octet-stream";
        };
    }

    /**
     * 浏览器能不能直接播。
     *
     * <p>MKV、AVI、WMA 这些容器主流浏览器都不支持，前端拿到 playable=false
     * 就只提供下载而不是给个永远转圈的播放器。
     */
    public boolean playableInBrowser(String ext) {
        return switch (normalizeExt(ext)) {
            case "mkv", "avi", "wma", "3gp", "hevc" -> false;
            default -> true;
        };
    }

    public static String normalizeExt(String ext) {
        if (ext == null) {
            return "";
        }
        String value = ext.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith(".")) {
            value = value.substring(1);
        }
        // 只留字母数字：扩展名是从用户提供的文件名里切出来的
        return value.replaceAll("[^a-z0-9]", "");
    }

    public static String extOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : normalizeExt(filename.substring(dot + 1));
    }

    /** 相对路径，存进 media.rel_path。 */
    public String relativePath(long ownerId, String sha256, String ext) {
        return ownerId + "/" + sha256.substring(0, 2) + "/" + sha256 + "." + normalizeExt(ext);
    }

    public Path resolve(Media media) {
        return paths.mediaDir().resolve(media.relPath());
    }

    public Path resolve(String relPath) {
        return paths.mediaDir().resolve(relPath);
    }

    /**
     * 把临时文件移到最终位置。
     *
     * <p>先尝试原子移动。跨文件系统时 ATOMIC_MOVE 会失败（临时目录和媒体目录
     * 可能不在同一个盘上），退回复制+删除。
     */
    public void store(Path temp, String relPath) throws IOException {
        Path target = paths.mediaDir().resolve(relPath);
        // 即便 relPath 是服务端生成的，也再校验一次不越界——这是纵深防御
        FileSecurity.createSecureDirectory(target.getParent());
        Path checked = FileSecurity.requireInside(paths.mediaDir(), target);

        try {
            Files.move(temp, checked, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.copy(temp, checked, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(temp);
        }
        FileSecurity.hardenFile(checked);
    }

    public void delete(Media media) {
        try {
            Path file = FileSecurity.requireInside(paths.mediaDir(), resolve(media));
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // 删不掉不该阻断数据库侧的清理：留个孤儿文件比留个删不掉的记录好
            log.warn("删除媒体文件失败 id={}: {}", media.id(), e.getMessage());
        }
    }

    public Path tempDir() {
        return paths.tempDir();
    }
}
