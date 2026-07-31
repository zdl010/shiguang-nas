package com.shiguang.nas.media;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Repository
public class MediaRepository {

    private static final String COLUMNS = """
            id, owner_id, sha256, kind, mime, ext, orig_name, size_bytes, width, height,
            duration_ms, taken_at, created_at, rel_path, thumb_state, playable, starred, deleted_at
            """;

    private final JdbcClient jdbc;

    public MediaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Media map(ResultSet rs, int rowNum) throws SQLException {
        return new Media(
                rs.getLong("id"),
                rs.getLong("owner_id"),
                rs.getString("sha256"),
                rs.getString("kind"),
                rs.getString("mime"),
                rs.getString("ext"),
                rs.getString("orig_name"),
                rs.getLong("size_bytes"),
                nullableInt(rs, "width"),
                nullableInt(rs, "height"),
                nullableLong(rs, "duration_ms"),
                nullableLong(rs, "taken_at"),
                rs.getLong("created_at"),
                rs.getString("rel_path"),
                rs.getString("thumb_state"),
                rs.getInt("playable") != 0,
                rs.getInt("starred") != 0,
                nullableLong(rs, "deleted_at"));
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    // ── 写 ──────────────────────────────────────────────────────────────

    public long insert(Media media) {
        jdbc.sql("""
                INSERT INTO media(owner_id, sha256, kind, mime, ext, orig_name, size_bytes,
                                  width, height, duration_ms, taken_at, created_at, rel_path,
                                  thumb_state, playable, starred)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """)
                .params(media.ownerId(), media.sha256(), media.kind(), media.mime(), media.ext(),
                        media.origName(), media.sizeBytes(), media.width(), media.height(),
                        media.durationMs(), media.takenAt(), media.createdAt(), media.relPath(),
                        media.thumbState(), media.playable() ? 1 : 0)
                .update();
        Long id = jdbc.sql("SELECT id FROM media WHERE owner_id = ? AND sha256 = ?")
                .params(media.ownerId(), media.sha256())
                .query(Long.class)
                .single();
        return id == null ? -1 : id;
    }

    public void updateThumbState(long id, String state, String error) {
        jdbc.sql("UPDATE media SET thumb_state = ?, thumb_error = ? WHERE id = ?")
                .params(state, error, id)
                .update();
    }

    public void updateProbe(long id, Integer width, Integer height, Long durationMs, Long takenAt) {
        jdbc.sql("""
                UPDATE media SET width = ?, height = ?, duration_ms = ?,
                                 taken_at = COALESCE(?, taken_at)
                WHERE id = ?
                """)
                .params(width, height, durationMs, takenAt, id)
                .update();
    }

    public int setStarred(long ownerId, List<Long> ids, boolean starred) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("UPDATE media SET starred = ? WHERE owner_id = ? AND id IN (" + placeholders(ids) + ")")
                .params(prepend(starred ? 1 : 0, ownerId, ids))
                .update();
    }

    /** 软删除：只打时间戳，文件还在盘上，回收站里能恢复。 */
    public int softDelete(long ownerId, List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("UPDATE media SET deleted_at = ? WHERE owner_id = ? AND deleted_at IS NULL AND id IN ("
                + placeholders(ids) + ")")
                .params(prepend(System.currentTimeMillis(), ownerId, ids))
                .update();
    }

    public int restore(long ownerId, List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("UPDATE media SET deleted_at = NULL WHERE owner_id = ? AND id IN ("
                + placeholders(ids) + ")")
                .params(prepend(null, ownerId, ids))
                .update();
    }

    public int hardDelete(long ownerId, List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM media WHERE owner_id = ? AND id IN (" + placeholders(ids) + ")")
                .params(prepend(null, ownerId, ids))
                .update();
    }

    // ── 读 ──────────────────────────────────────────────────────────────

    public Optional<Media> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM media WHERE id = ?")
                .param(id)
                .query(MediaRepository::map)
                .optional();
    }

    public Optional<Media> findBySha(long ownerId, String sha256) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM media WHERE owner_id = ? AND sha256 = ?")
                .params(ownerId, sha256)
                .query(MediaRepository::map)
                .optional();
    }

    public List<Media> findAll(long ownerId, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM media WHERE owner_id = ? AND id IN ("
                + placeholders(ids) + ")")
                .params(prepend(null, ownerId, ids))
                .query(MediaRepository::map)
                .list();
    }

    /** 待生成缩略图的条目，供后台 worker 领取。 */
    public List<Media> findPendingThumbs(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM media WHERE thumb_state = 'PENDING' AND deleted_at IS NULL
                 ORDER BY id LIMIT ?
                """)
                .param(limit)
                .query(MediaRepository::map)
                .list();
    }

    public List<Media> findExpiredTrash(long cutoff) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM media WHERE deleted_at IS NOT NULL AND deleted_at < ?")
                .param(cutoff)
                .query(MediaRepository::map)
                .list();
    }

    /**
     * 六视图的统一查询。
     *
     * <p>游标分页而不是 OFFSET：OFFSET 每翻一页都要让 SQLite 数着跳过前 N 行，
     * 到第 100 页就是扫 5000 行。而且新上传的照片会插到最前面，让 OFFSET 分页
     * 出现重复项。游标 {@code (taken_at, id)} 直接命中 ix_media_feed 索引，
     * 翻到多少页都是同样的代价。
     *
     * @param cursor 上一页最后一项的 "时间_id"，首页传 null
     */
    public List<Media> feed(long ownerId, String view, String keyword, String cursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(prefixed("m")).append(" FROM media m ");

        List<Object> params = new ArrayList<>();

        String needle = keyword == null ? "" : keyword.strip();
        boolean searching = !needle.isEmpty();
        // trigram 分词器需要至少 3 个字符才能切出 token，更短的查询只能扫表。
        // 见 V3 迁移里的说明。
        boolean useFts = needle.length() >= 3;
        if (searching && useFts) {
            sql.append("JOIN media_fts f ON f.rowid = m.id ");
        }

        sql.append("WHERE m.owner_id = ? ");
        params.add(ownerId);

        if ("trash".equals(view)) {
            sql.append("AND m.deleted_at IS NOT NULL ");
        } else {
            sql.append("AND m.deleted_at IS NULL ");
        }

        switch (view == null ? "all" : view) {
            case "photo" -> { sql.append("AND m.kind = ? "); params.add(Media.KIND_PHOTO); }
            case "video" -> { sql.append("AND m.kind = ? "); params.add(Media.KIND_VIDEO); }
            case "audio" -> { sql.append("AND m.kind = ? "); params.add(Media.KIND_AUDIO); }
            case "fav" -> sql.append("AND m.starred = 1 ");
            default -> { /* all / trash 不加额外条件 */ }
        }

        if (searching) {
            if (useFts) {
                sql.append("AND media_fts MATCH ? ");
                params.add(toFtsQuery(needle));
            } else {
                // 1-2 个字符的查询。前置通配的 LIKE 用不上索引，是一次全表扫描，
                // 但个人相册规模下（几万行）只有几十毫秒，可以接受。
                // ESCAPE 是必须的：文件名里的 % 和 _ 否则会被当成通配符。
                sql.append("AND m.orig_name LIKE ? ESCAPE '\\' ");
                params.add("%" + escapeLike(needle) + "%");
            }
        }

        long[] decoded = decodeCursor(cursor);
        if (decoded != null) {
            // 与 ORDER BY 完全对应的元组比较。写成 (a < ? OR (a = ? AND b < ?)) 而不是
            // SQLite 的行值比较，是因为行值语法在旧版本上不可用。
            sql.append("AND (COALESCE(m.taken_at, m.created_at) < ? ")
               .append("OR (COALESCE(m.taken_at, m.created_at) = ? AND m.id < ?)) ");
            params.add(decoded[0]);
            params.add(decoded[0]);
            params.add(decoded[1]);
        }

        sql.append("ORDER BY COALESCE(m.taken_at, m.created_at) DESC, m.id DESC LIMIT ?");
        params.add(limit);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(MediaRepository::map)
                .list();
    }

    /** 各视图的条目数，用于侧边栏角标。 */
    public Map<String, Long> counts(long ownerId) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT
                  SUM(CASE WHEN deleted_at IS NULL THEN 1 ELSE 0 END)                      AS all_count,
                  SUM(CASE WHEN deleted_at IS NULL AND kind = 'PHOTO' THEN 1 ELSE 0 END)   AS photo_count,
                  SUM(CASE WHEN deleted_at IS NULL AND kind = 'VIDEO' THEN 1 ELSE 0 END)   AS video_count,
                  SUM(CASE WHEN deleted_at IS NULL AND kind = 'AUDIO' THEN 1 ELSE 0 END)   AS audio_count,
                  SUM(CASE WHEN deleted_at IS NULL AND starred = 1 THEN 1 ELSE 0 END)      AS fav_count,
                  SUM(CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END)                  AS trash_count,
                  SUM(CASE WHEN deleted_at IS NULL THEN size_bytes ELSE 0 END)             AS used_bytes,
                  SUM(CASE WHEN deleted_at IS NULL AND kind = 'PHOTO' THEN size_bytes ELSE 0 END) AS photo_bytes,
                  SUM(CASE WHEN deleted_at IS NULL AND kind = 'VIDEO' THEN size_bytes ELSE 0 END) AS video_bytes,
                  SUM(CASE WHEN deleted_at IS NULL AND kind = 'AUDIO' THEN size_bytes ELSE 0 END) AS audio_bytes
                FROM media WHERE owner_id = ?
                """)
                .param(ownerId)
                .query((rs, n) -> {
                    result.put("all", rs.getLong("all_count"));
                    result.put("photo", rs.getLong("photo_count"));
                    result.put("video", rs.getLong("video_count"));
                    result.put("audio", rs.getLong("audio_count"));
                    result.put("fav", rs.getLong("fav_count"));
                    result.put("trash", rs.getLong("trash_count"));
                    result.put("usedBytes", rs.getLong("used_bytes"));
                    result.put("photoBytes", rs.getLong("photo_bytes"));
                    result.put("videoBytes", rs.getLong("video_bytes"));
                    result.put("audioBytes", rs.getLong("audio_bytes"));
                    return result;
                })
                .optional();
        return result;
    }

    // ── 游标与查询串 ────────────────────────────────────────────────────

    public static String encodeCursor(Media media) {
        return media.effectiveTime() + "_" + media.id();
    }

    private static long[] decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int sep = cursor.indexOf('_');
        if (sep <= 0) {
            return null;
        }
        try {
            return new long[]{
                    Long.parseLong(cursor.substring(0, sep)),
                    Long.parseLong(cursor.substring(sep + 1))};
        } catch (NumberFormatException e) {
            // 游标是客户端传来的，坏了就当没有游标从头开始，不该 500
            return null;
        }
    }

    /**
     * 把用户输入转成 FTS5 查询串。
     *
     * <p>必须转义：FTS5 的 MATCH 有自己的语法（AND/OR/NEAR/引号/星号），
     * 用户输入里带个双引号就会变成语法错误，带 {@code *} 则可能拖垮查询。
     * 这里把输入整体当作短语并加前缀通配，既安全又符合"搜文件名"的直觉。
     */
    private static String toFtsQuery(String keyword) {
        String cleaned = keyword.strip().replace("\"", "");
        if (cleaned.isEmpty()) {
            return "\"\"";
        }
        // trigram 分词器下，加引号的短语查询就是子串匹配，不需要也不能加 * 通配
        return "\"" + cleaned.toLowerCase(Locale.ROOT) + "\"";
    }

    /** 转义 LIKE 的通配符，避免文件名里的 % 和 _ 被当成模式。 */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String prefixed(String alias) {
        StringBuilder out = new StringBuilder();
        for (String column : COLUMNS.replace("\n", " ").split(",")) {
            String name = column.strip();
            if (name.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(alias).append('.').append(name);
        }
        return out.toString();
    }

    private static String placeholders(List<Long> ids) {
        return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    }

    /** 把前置参数和 id 列表拼成一个参数数组。null 表示该位置不占参数。 */
    private static List<Object> prepend(Object first, long ownerId, List<Long> ids) {
        List<Object> params = new ArrayList<>(ids.size() + 2);
        if (first != null) {
            params.add(first);
        }
        params.add(ownerId);
        params.addAll(ids);
        return params;
    }
}
