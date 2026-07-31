package com.shiguang.nas.media;

import com.shiguang.nas.common.ApiException;
import com.shiguang.nas.common.FileSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 分片上传：init → chunk × N → complete。
 *
 * <p>为什么不用一次 POST 把整个文件传上来：手机在局域网里传一个 2GB 的视频，
 * 中途切一次 WiFi 就得从头再来。分片让断点续传成为可能，也让进度条是真的。
 *
 * <p>秒传：init 时带上整文件的 sha256，如果该用户已经传过同一个文件就直接返回
 * 已有记录，一个字节都不用传。哈希按 owner 隔离查询——全局查的话，
 * 「传一个文件看它是不是秒传」就变成了探测别人有没有某个文件的接口。
 *
 * <p><b>单文件没有大小上限</b>，实际的限制是磁盘剩余空间。为此有两处实现是刻意这样写的：
 * <ul>
 *   <li>分片<b>直接按偏移量写进同一个目标文件</b>，不再是"每片存一个小文件、最后合并成大文件"。
 *       后者在完成时需要 2 倍文件大小的磁盘空间（分片 + 合并结果），
 *       传一个 200GB 的视频就得有 400GB 空闲。
 *   <li>已收到的分片记在 {@code upload_chunks} 表里而不是一个逗号分隔的字符串，
 *       否则每收一片都要重写整串，累计代价是 O(n²)。
 * </ul>
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    /** 分片大小。5MB 是手机端网络抖动和请求开销之间的折中。 */
    public static final int CHUNK_SIZE = 5 * 1024 * 1024;

    /** 未完成的上传会话多久过期。太短会让大文件传一半被清掉。 */
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    private final JdbcClient jdbc;
    private final MediaRepository mediaRepository;
    private final MediaStorage storage;
    private final ThumbnailService thumbnails;

    public UploadService(JdbcClient jdbc, MediaRepository mediaRepository, MediaStorage storage,
                         ThumbnailService thumbnails) {
        this.jdbc = jdbc;
        this.mediaRepository = mediaRepository;
        this.storage = storage;
        this.thumbnails = thumbnails;
    }

    public record InitResult(String uploadId, int chunkSize, int chunkTotal,
                             List<Integer> receivedChunks, Long existingMediaId) {
    }

    // ── init ────────────────────────────────────────────────────────────

    public InitResult init(long ownerId, String sha256, String origName, long totalSize) {
        String hash = normalizeSha(sha256);
        String ext = MediaStorage.extOf(origName);

        if (!storage.supported(ext)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "不支持的文件类型：." + (ext.isEmpty() ? "(无扩展名)" : ext));
        }
        // 不设大小上限，但 0 字节和负数是明显的畸形请求，收下只会在后面炸得更难查
        if (totalSize <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "文件大小不合法");
        }
        ensureDiskSpace(totalSize);

        // 秒传
        Optional<Media> existing = mediaRepository.findBySha(ownerId, hash);
        if (existing.isPresent()) {
            return new InitResult(null, CHUNK_SIZE, 0, List.of(), existing.get().id());
        }

        int chunkTotal = (int) ((totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE);

        // 断点续传：同一个 (owner, sha) 复用未过期的会话，把已收到的分片号告诉客户端
        Optional<String> resumable = jdbc.sql("""
                SELECT id FROM upload_sessions
                WHERE user_id = ? AND sha256 = ? AND expires_at > ?
                ORDER BY created_at DESC LIMIT 1
                """)
                .params(ownerId, hash, System.currentTimeMillis())
                .query(String.class)
                .optional();

        if (resumable.isPresent()) {
            String id = resumable.get();
            return new InitResult(id, CHUNK_SIZE, chunkTotal, receivedChunks(id), null);
        }

        String uploadId = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        jdbc.sql("""
                INSERT INTO upload_sessions(id, user_id, sha256, orig_name, total_size,
                                            chunk_size, chunk_total, received, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, '', ?, ?)
                """)
                .params(uploadId, ownerId, hash, safeName(origName), totalSize,
                        CHUNK_SIZE, chunkTotal, now, now + SESSION_TTL.toMillis())
                .update();

        return new InitResult(uploadId, CHUNK_SIZE, chunkTotal, List.of(), null);
    }

    /**
     * 磁盘装不下就当场拒绝，而不是让用户传两小时之后在最后一片上失败。
     *
     * <p>留 1GB 余量：数据库、缩略图、系统本身都还要写东西，把盘塞到一个字节不剩
     * 会让 SQLite 先挂掉。
     */
    private void ensureDiskSpace(long totalSize) {
        try {
            long usable = Files.getFileStore(storage.tempDir()).getUsableSpace();
            long required = totalSize + (1L << 30);
            if (usable > 0 && usable < required) {
                throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE,
                        "磁盘剩余空间不足（还需 " + (required - usable) / (1L << 20) + " MB）");
            }
        } catch (IOException e) {
            // 读不到剩余空间就别挡着，真写满时下面的 IO 会报错
            log.debug("无法读取磁盘剩余空间: {}", e.getMessage());
        }
    }

    // ── chunk ───────────────────────────────────────────────────────────

    /**
     * 收一片，<b>直接写进目标文件的对应偏移量</b>。
     *
     * <p>并发写同一个文件是安全的：{@link FileChannel#write(ByteBuffer, long)} 是
     * 带位置的写，不依赖也不修改通道的共享位置。各片偏移互不重叠。
     */
    public void acceptChunk(long ownerId, String uploadId, int index, MultipartFile part) {
        UploadSession session = loadSession(ownerId, uploadId);
        if (index < 0 || index >= session.chunkTotal()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "分片序号越界");
        }

        // 校验分片长度。不校验的话，一个长度不对的分片会把后面所有数据的偏移量写错，
        // 最终表现为"哈希校验失败"，而真正的原因完全看不出来。
        long offset = (long) index * CHUNK_SIZE;
        long expected = Math.min(CHUNK_SIZE, session.totalSize() - offset);
        if (part.getSize() != expected) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "第 " + (index + 1) + " 片大小不符（应为 " + expected + " 字节，收到 " + part.getSize() + "）");
        }

        Path target = partialFile(uploadId);
        try {
            FileSecurity.createSecureDirectory(target.getParent());
            try (FileChannel channel = FileChannel.open(target,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 InputStream in = part.getInputStream()) {

                byte[] buffer = new byte[64 * 1024];
                long position = offset;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    ByteBuffer wrapper = ByteBuffer.wrap(buffer, 0, read);
                    while (wrapper.hasRemaining()) {
                        position += channel.write(wrapper, position);
                    }
                }
            }
            FileSecurity.hardenFile(target);
        } catch (IOException e) {
            log.error("写入分片失败 uploadId={} index={}", uploadId, index, e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "分片写入失败");
        }

        markReceived(uploadId, index);
    }

    // ── complete ────────────────────────────────────────────────────────

    /**
     * 校验哈希并入库。
     *
     * <p>哈希校验不是可选项：客户端声称的 sha256 决定了文件落在哪个路径、
     * 以及会不会被当成"已存在"而秒传。不校验的话，攻击者可以声称一个
     * 已存在文件的哈希，然后上传任意内容把它覆盖掉。
     */
    public Media complete(long ownerId, String uploadId) {
        UploadSession session = loadSession(ownerId, uploadId);

        int missing = firstMissingChunk(uploadId, session.chunkTotal());
        if (missing >= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "还有分片没有上传完（缺第 " + (missing + 1) + " 片）");
        }

        Path assembled = partialFile(uploadId);
        String actualHash;
        try {
            long actualSize = Files.size(assembled);
            if (actualSize != session.totalSize()) {
                cleanup(uploadId);
                throw new ApiException(HttpStatus.BAD_REQUEST, "文件大小与声明不符");
            }
            actualHash = hashFile(assembled);
        } catch (IOException e) {
            cleanup(uploadId);
            log.error("校验上传文件失败 uploadId={}", uploadId, e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "文件校验失败");
        }

        if (!actualHash.equals(session.sha256())) {
            cleanup(uploadId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "文件校验失败，请重新上传");
        }

        try {
            // 并发上传同一个文件时，另一个请求可能已经入库了
            Optional<Media> existing = mediaRepository.findBySha(ownerId, actualHash);
            if (existing.isPresent()) {
                cleanup(uploadId);
                return existing.get();
            }

            String ext = MediaStorage.extOf(session.origName());
            String relPath = storage.relativePath(ownerId, actualHash, ext);
            // temp 和 media 都在存储根目录下，同一个卷，这一步是重命名而不是复制——
            // 大文件在这里不会再多占一份空间，也不会多花时间
            storage.store(assembled, relPath);

            String kind = storage.kindOf(ext);
            Media media = new Media(0, ownerId, actualHash, kind, storage.mimeOf(ext), ext,
                    session.origName(), session.totalSize(), null, null, null, null,
                    System.currentTimeMillis(), relPath, Media.THUMB_PENDING,
                    storage.playableInBrowser(ext), false, null);

            long id = mediaRepository.insert(media);
            deleteSession(uploadId);

            // 让缩略图尽快出来，不用等下一次定时轮询
            thumbnails.nudge();

            return mediaRepository.find(id).orElseThrow();
        } catch (IOException e) {
            cleanup(uploadId);
            log.error("保存媒体文件失败 uploadId={}", uploadId, e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "保存文件失败");
        }
    }

    public void abort(long ownerId, String uploadId) {
        loadSession(ownerId, uploadId);
        cleanup(uploadId);
        deleteSession(uploadId);
    }

    /** 清理过期的上传会话与残留文件，由定时任务调用。 */
    public int purgeExpired() {
        List<String> expired = jdbc.sql("SELECT id FROM upload_sessions WHERE expires_at < ?")
                .param(System.currentTimeMillis())
                .query(String.class)
                .list();
        expired.forEach(this::cleanup);
        expired.forEach(this::deleteSession);
        return expired.size();
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private record UploadSession(String id, long userId, String sha256, String origName,
                                 long totalSize, int chunkTotal) {
    }

    private UploadSession loadSession(long ownerId, String uploadId) {
        return jdbc.sql("""
                SELECT id, user_id, sha256, orig_name, total_size, chunk_total
                FROM upload_sessions WHERE id = ? AND user_id = ? AND expires_at > ?
                """)
                .params(uploadId, ownerId, System.currentTimeMillis())
                .query((rs, n) -> new UploadSession(
                        rs.getString("id"), rs.getLong("user_id"), rs.getString("sha256"),
                        rs.getString("orig_name"), rs.getLong("total_size"),
                        rs.getInt("chunk_total")))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "上传会话不存在或已过期"));
    }

    /** 记下"第 i 片收到了"。重复上传同一片是允许的（客户端重试），所以用 OR IGNORE。 */
    private void markReceived(String uploadId, int index) {
        jdbc.sql("INSERT OR IGNORE INTO upload_chunks(upload_id, chunk_index, received_at) VALUES (?, ?, ?)")
                .params(uploadId, index, System.currentTimeMillis())
                .update();
    }

    private List<Integer> receivedChunks(String uploadId) {
        return jdbc.sql("SELECT chunk_index FROM upload_chunks WHERE upload_id = ? ORDER BY chunk_index")
                .param(uploadId)
                .query(Integer.class)
                .list();
    }

    /**
     * 第一个没收到的分片号，全收齐返回 -1。
     *
     * <p>只查一次 COUNT 再定位缺口，不把几万个分片号读进内存。
     */
    private int firstMissingChunk(String uploadId, int chunkTotal) {
        Long received = jdbc.sql("SELECT COUNT(*) FROM upload_chunks WHERE upload_id = ?")
                .param(uploadId)
                .query(Long.class)
                .single();
        if (received != null && received == chunkTotal) {
            return -1;
        }
        // 有缺口才去找具体是哪一个。这个查询只在"没传完就调 complete"时才跑。
        Integer gap = jdbc.sql("""
                SELECT MIN(c.chunk_index + 1) FROM upload_chunks c
                WHERE c.upload_id = ?
                  AND c.chunk_index + 1 < ?
                  AND NOT EXISTS (SELECT 1 FROM upload_chunks n
                                  WHERE n.upload_id = c.upload_id AND n.chunk_index = c.chunk_index + 1)
                """)
                .params(uploadId, chunkTotal)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (gap != null) {
            return gap;
        }
        // 没有中间缺口，那就是从 0 开始或者末尾还差
        Integer min = jdbc.sql("SELECT MIN(chunk_index) FROM upload_chunks WHERE upload_id = ?")
                .param(uploadId).query(Integer.class).optional().orElse(null);
        if (min == null || min > 0) {
            return 0;
        }
        Integer max = jdbc.sql("SELECT MAX(chunk_index) FROM upload_chunks WHERE upload_id = ?")
                .param(uploadId).query(Integer.class).optional().orElse(-1);
        return max + 1 < chunkTotal ? max + 1 : -1;
    }

    /** 正在拼装中的文件。分片按偏移量直接写进这里，完成后整体改名到媒体目录。 */
    private Path partialFile(String uploadId) {
        return storage.tempDir().resolve(uploadId + ".partial");
    }

    /** 流式算哈希，不把文件读进内存——这里的文件可能有几十上百 GB。 */
    private static String hashFile(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
        byte[] buffer = new byte[1 << 20];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void cleanup(String uploadId) {
        try {
            Files.deleteIfExists(partialFile(uploadId));
        } catch (IOException e) {
            log.debug("清理上传临时文件失败: {}", e.getMessage());
        }
        jdbc.sql("DELETE FROM upload_chunks WHERE upload_id = ?").param(uploadId).update();
    }

    private void deleteSession(String uploadId) {
        jdbc.sql("DELETE FROM upload_chunks WHERE upload_id = ?").param(uploadId).update();
        jdbc.sql("DELETE FROM upload_sessions WHERE id = ?").param(uploadId).update();
    }

    private static String normalizeSha(String sha) {
        if (sha == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少文件校验和");
        }
        String value = sha.strip().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "文件校验和格式不正确");
        }
        return value;
    }

    /**
     * 原始文件名只用于展示，但仍要清洗后再存。
     *
     * <p>去掉路径分隔符和控制字符：有些浏览器（尤其是安卓上的文件管理器）
     * 会把完整路径塞进 filename，直接存下来会在界面上泄露用户的目录结构。
     */
    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "未命名";
        }
        String cleaned = name.replaceAll("[\\p{Cntrl}]", "")
                .replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.strip();
        if (cleaned.isEmpty()) {
            return "未命名";
        }
        return cleaned.length() <= 200 ? cleaned : cleaned.substring(0, 200);
    }
}
