package com.shiguang.nas.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigratorTest {

    // ── splitStatements ────────────────────────────────────────────────

    @Test
    void 按分号切分普通语句() {
        List<String> statements = SchemaMigrator.splitStatements("""
                CREATE TABLE a (id INTEGER);
                CREATE TABLE b (id INTEGER);
                """);

        assertThat(statements).containsExactly(
                "CREATE TABLE a (id INTEGER);",
                "CREATE TABLE b (id INTEGER);");
    }

    /**
     * 这是 splitStatements 存在的全部理由：触发器体里的分号不是语句结束符。
     * 按分号裸切会把一个触发器切成三段废 SQL。
     */
    @Test
    void 不切分触发器体内部的分号() {
        List<String> statements = SchemaMigrator.splitStatements("""
                CREATE TABLE media (id INTEGER, name TEXT);
                CREATE TRIGGER media_ai AFTER INSERT ON media BEGIN
                  INSERT INTO media_fts(rowid, name) VALUES (new.id, new.name);
                  UPDATE counters SET n = n + 1;
                END;
                CREATE INDEX ix_media_name ON media(name);
                """);

        assertThat(statements).hasSize(3);
        assertThat(statements.get(1))
                .startsWith("CREATE TRIGGER media_ai")
                .contains("INSERT INTO media_fts")
                .contains("UPDATE counters")
                .endsWith("END;");
    }

    @Test
    void 剥掉行注释且不产生空语句() {
        List<String> statements = SchemaMigrator.splitStatements("""
                -- 整行都是注释
                CREATE TABLE a (id INTEGER); -- 行尾注释

                -- 又一段注释

                CREATE TABLE b (id INTEGER);
                """);

        assertThat(statements).containsExactly(
                "CREATE TABLE a (id INTEGER);",
                "CREATE TABLE b (id INTEGER);");
    }

    /** 最后一条语句忘了写分号是常见笔误，不能因此把它丢掉 */
    @Test
    void 保留末尾没有分号的语句() {
        assertThat(SchemaMigrator.splitStatements("CREATE TABLE a (id INTEGER)"))
                .containsExactly("CREATE TABLE a (id INTEGER)");
    }

    @Test
    void 空脚本切出空列表() {
        assertThat(SchemaMigrator.splitStatements("")).isEmpty();
        assertThat(SchemaMigrator.splitStatements("\n\n-- 只有注释\n")).isEmpty();
    }

    // ── migrate ────────────────────────────────────────────────────────

    @Test
    void 迁移建出全部业务表且可重复执行(@TempDir Path dir) throws Exception {
        DataSource dataSource = sqliteAt(dir.resolve("test.db"));

        new SchemaMigrator(dataSource).migrate();
        // 再跑一次：已应用的版本应被跳过而不是重复建表（重复建表会直接抛 SQLException）
        new SchemaMigrator(dataSource).migrate();

        assertThat(tableNames(dataSource)).contains(
                "users", "user_sessions", "media", "media_exif", "upload_sessions",
                "albums", "album_items", "audit_log", "settings",
                "media_fts", "schema_version");

        // V5 删掉的东西必须真的没了，不然"删干净"就只是句口号
        assertThat(tableNames(dataSource)).doesNotContain("invite_codes");
        assertThat(columnNames(dataSource, "users"))
                .contains("must_change_password")
                .doesNotContain("totp_secret");

        // 不写死版本号：每加一个迁移脚本就要来改一次断言的测试，
        // 除了制造噪音没有别的作用。这里验的是"全部脚本都被应用了且不重不漏"。
        assertThat(appliedVersions(dataSource))
                .isNotEmpty()
                .isSorted()
                .doesNotHaveDuplicates()
                .startsWith(1);
    }

    /**
     * 校验和防篡改：已执行过的迁移脚本被改动后必须拒绝启动。
     * 这里反过来改数据库里的校验和，效果等价，且不用去动 classpath 里的资源。
     */
    @Test
    void 校验和不符时拒绝继续(@TempDir Path dir) throws Exception {
        DataSource dataSource = sqliteAt(dir.resolve("test.db"));
        new SchemaMigrator(dataSource).migrate();

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE schema_version SET checksum = 'tampered' WHERE version = 1");
        }

        assertThatThrownBy(() -> new SchemaMigrator(dataSource).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("校验和不符");
    }

    private static DataSource sqliteAt(Path file) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return ds;
    }

    private static List<String> tableNames(DataSource dataSource) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table'")) {
            List<String> names = new java.util.ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            return names;
        }
    }

    private static List<String> columnNames(DataSource dataSource, String table) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            List<String> names = new java.util.ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
            return names;
        }
    }

    private static List<Integer> appliedVersions(DataSource dataSource) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM schema_version ORDER BY version")) {
            List<Integer> versions = new java.util.ArrayList<>();
            while (rs.next()) {
                versions.add(rs.getInt(1));
            }
            return versions;
        }
    }

    /**
     * CRLF 的迁移文件必须和 LF 切出完全一样的语句。
     *
     * <p>仓库里没有 .gitattributes 时，Windows 上 git 默认把 LF 换成 CRLF；
     * 而 Java 正则的 {@code .} 不匹配 {@code \r}，会让 BEGIN...END 的判断失效，
     * 触发器被从中间切断。这个坑只在 Windows 上现形，本机永远测不出来。
     */
    @Test
    void CRLF与LF切出相同的语句() throws Exception {
        String lf = new String(getClass().getResourceAsStream(
                "/db/migration/V2__media_fts.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        String crlf = lf.replace("\n", "\r\n");

        var fromLf = SchemaMigrator.splitStatements(SchemaMigrator.normalizeNewlines(lf));
        var fromCrlf = SchemaMigrator.splitStatements(SchemaMigrator.normalizeNewlines(crlf));

        assertThat(fromCrlf).isEqualTo(fromLf);
        // 触发器不能被切开：每条 BEGIN 都得有配对的 END
        for (String st : fromLf) {
            long begins = st.toUpperCase(java.util.Locale.ROOT).split("\\bBEGIN\\b", -1).length - 1;
            long ends = st.toUpperCase(java.util.Locale.ROOT).split("\\bEND\\b", -1).length - 1;
            assertThat(ends).as("语句里 BEGIN/END 必须配对: %s", st).isEqualTo(begins);
        }
    }

    /** 校验和不能因为换行风格不同而变化，否则换台机器就报"迁移已被修改" */
    @Test
    void 校验和不受换行风格影响() {
        String lf = "CREATE TABLE t(a);\nINSERT INTO t VALUES(1);\n";
        assertThat(SchemaMigrator.normalizeNewlines(lf.replace("\n", "\r\n")))
                .isEqualTo(SchemaMigrator.normalizeNewlines(lf));
    }

}
