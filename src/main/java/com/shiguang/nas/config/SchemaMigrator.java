package com.shiguang.nas.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简数据库迁移器，用来替代 Flyway。
 *
 * <p>不引 Flyway 有两个原因：一是 {@code flyway-database-sqlite} 在 Maven Central 上并不存在，
 * SQLite 支持本来就要绕；二是迁移逻辑总共一百来行，自己维护比引一个大依赖更符合最小攻击面原则。
 *
 * <p>行为对齐 Flyway 的核心语义：
 * <ul>
 *   <li>扫描 {@code classpath:db/migration/V&lt;版本号&gt;__&lt;描述&gt;.sql}，按版本号升序执行
 *   <li>每个脚本在**单个事务**内执行，失败则整体回滚
 *   <li>记录 SHA-256 校验和；已执行过的脚本若被改动则拒绝启动（防止线上库和代码悄悄不一致）
 * </ul>
 */
public final class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private static final Pattern FILE_PATTERN = Pattern.compile("^V(\\d+)__(.+)\\.sql$");
    private static final String LOCATION = "classpath*:db/migration/V*__*.sql";

    private final DataSource dataSource;

    public SchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private record Migration(int version, String name, String sql, String checksum) {
    }

    public void migrate() {
        List<Migration> migrations = loadMigrations();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            ensureHistoryTable(conn);
            Map<Integer, String> applied = loadApplied(conn);

            for (Migration m : migrations) {
                String previous = applied.get(m.version());
                if (previous != null) {
                    if (!previous.equals(m.checksum())) {
                        throw new IllegalStateException(
                                "迁移脚本 V%d__%s.sql 在已执行后被修改（校验和不符）。"
                                        .formatted(m.version(), m.name())
                                        + "请新增一个版本号更高的脚本，而不是改动历史脚本。");
                    }
                    continue;
                }
                apply(conn, m);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("数据库迁移失败", e);
        }
    }

    private void apply(Connection conn, Migration m) throws SQLException {
        log.info("执行数据库迁移 V{} · {}", m.version(), m.name());
        long start = System.nanoTime();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            for (String statement : splitStatements(m.sql())) {
                st.execute(statement);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO schema_version(version, name, checksum, applied_at, took_ms) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                ps.setInt(1, m.version());
                ps.setString(2, m.name());
                ps.setString(3, m.checksum());
                ps.setLong(4, System.currentTimeMillis());
                ps.setLong(5, (System.nanoTime() - start) / 1_000_000);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw new SQLException("迁移 V%d__%s.sql 执行失败".formatted(m.version(), m.name()), e);
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private void ensureHistoryTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                      version    INTEGER PRIMARY KEY,
                      name       TEXT    NOT NULL,
                      checksum   TEXT    NOT NULL,
                      applied_at INTEGER NOT NULL,
                      took_ms    INTEGER NOT NULL
                    )""");
        }
    }

    private Map<Integer, String> loadApplied(Connection conn) throws SQLException {
        Map<Integer, String> applied = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT version, checksum FROM schema_version")) {
            while (rs.next()) {
                applied.put(rs.getInt(1), rs.getString(2));
            }
        }
        return applied;
    }

    private List<Migration> loadMigrations() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
            List<Migration> migrations = new ArrayList<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                Matcher matcher = FILE_PATTERN.matcher(filename);
                if (!matcher.matches()) {
                    log.warn("忽略命名不合规的迁移文件: {}", filename);
                    continue;
                }
                String sql;
                try (var in = resource.getInputStream()) {
                    sql = normalizeNewlines(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                migrations.add(new Migration(
                        Integer.parseInt(matcher.group(1)),
                        matcher.group(2),
                        sql,
                        sha256(sql)));
            }
            migrations.sort(Comparator.comparingInt(Migration::version));
            return migrations;
        } catch (IOException e) {
            throw new UncheckedIOException("读取迁移脚本失败", e);
        }
    }

    /**
     * 按分号切分语句，但跳过 {@code BEGIN ... END} 块内部的分号
     * （SQLite 的触发器体里带分号，直接切会把触发器切碎）。
     */

    /**
     * 把换行统一成 {@code \n}。
     *
     * <p>两个都必须修的理由：
     *
     * <p>1. <b>语句会被切错。</b>{@link #splitStatements} 用
     * {@code .matches(".*\\bBEGIN\\b.*")} 判断触发器的起止，而 Java 正则里的
     * {@code .} <b>不匹配 {@code \r}</b>（它算行终止符）。CRLF 的文件里
     * {@code ...BEGIN\r} 就匹配不上，blockDepth 加不上去，触发器内部的分号
     * 被当成语句结束，SQLite 收到半截 SQL 报 "incomplete input"。
     *
     * <p>2. <b>校验和会不一致。</b>仓库里没有 .gitattributes 时，Windows 上
     * git 默认把 LF 换成 CRLF，同一个迁移文件在两个平台上算出的 SHA-256 不同，
     * 于是换台机器就报"迁移文件已被修改"。归一之后校验和只取决于内容本身。
     *
     * <p>对本来就是 LF 的文件，这个函数不改变任何字节，因此已有数据库里
     * 记录的校验和继续有效。
     */
    static String normalizeNewlines(String text) {
        return text.indexOf('\r') < 0 ? text : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int blockDepth = 0;

        for (String rawLine : sql.split("\n", -1)) {
            String line = stripLineComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            String upper = line.toUpperCase(java.util.Locale.ROOT);
            // 只认独立成词的 BEGIN / END，避免匹配到 "BEGINNING" 之类的标识符
            if (upper.matches(".*\\bBEGIN\\b.*")) {
                blockDepth++;
            }
            if (upper.matches(".*\\bEND\\s*;.*")) {
                blockDepth = Math.max(0, blockDepth - 1);
            }
            current.append(line).append('\n');

            if (blockDepth == 0 && line.stripTrailing().endsWith(";")) {
                String statement = current.toString().strip();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private static String stripLineComment(String line) {
        int idx = line.indexOf("--");
        return idx >= 0 ? line.substring(0, idx) : line;
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
