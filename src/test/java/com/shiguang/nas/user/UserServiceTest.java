package com.shiguang.nas.user;

import com.shiguang.nas.common.ApiException;
import com.shiguang.nas.config.SchemaMigrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用户名与密码策略的回归测试。
 *
 * <p>跑真实 SQLite 而不是 mock 仓储：唯一性约束是策略的一部分，
 * mock 掉它等于把要验的东西验没了。
 */
class UserServiceTest {

    /**
     * 测试用的 bcrypt 代价因子刻意压到最低（4）。
     * 生产用 12（见 SecurityConfig），几十个用例按那个强度跑要十几秒，
     * 而这里要验的是策略分支，不是 KDF 强度。
     */
    private static final PasswordEncoder FAST_ENCODER = new BCryptPasswordEncoder(4);

    // 刻意不用 "Shiguang!2026"：产品名在弱词表里，是这台机器最该防的那个猜测
    private static final String VALID_PASSWORD = "Yunhe!Qiao2026";

    private UserService userService;
    private UserRepository userRepository;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("test.db").toAbsolutePath());
        DataSource dataSource = ds;
        new SchemaMigrator(dataSource).migrate();

        userRepository = new UserRepository(JdbcClient.create(dataSource));
        userService = new UserService(userRepository, FAST_ENCODER);
    }

    // ── 正常路径 ────────────────────────────────────────────────────────

    @Test
    void 创建管理员并存下bcrypt哈希() {
        UserAccount admin = userService.createAdmin("laoli", "老李", VALID_PASSWORD);

        assertThat(admin.username()).isEqualTo("laoli");
        assertThat(admin.displayName()).isEqualTo("老李");
        assertThat(admin.role()).isEqualTo(UserAccount.ROLE_ADMIN);
        assertThat(admin.isActive()).isTrue();
        assertThat(admin.failedCount()).isZero();

        // 明文绝不能出现在库里
        assertThat(admin.passwordHash())
                .startsWith("$2a$")
                .doesNotContain(VALID_PASSWORD);
        assertThat(FAST_ENCODER.matches(VALID_PASSWORD, admin.passwordHash())).isTrue();
    }

    @Test
    void 显示名留空时回落到用户名() {
        assertThat(userService.createUser("xiaozhang", "  ", VALID_PASSWORD).displayName())
                .isEqualTo("xiaozhang");
        assertThat(userService.createUser("xiaowang", null, VALID_PASSWORD).displayName())
                .isEqualTo("xiaowang");
    }

    @Test
    void 超长显示名被截到三十二字符() {
        String longName = "拾".repeat(100);
        assertThat(userService.createUser("xiaoli", longName, VALID_PASSWORD).displayName())
                .hasSize(32);
    }

    @Test
    void 普通用户角色为USER() {
        assertThat(userService.createUser("xiaozhao", "小赵", VALID_PASSWORD).role())
                .isEqualTo(UserAccount.ROLE_USER);
    }

    // ── 用户名策略 ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "ab",                       // 太短
            "aaaaaaaaaaaaaaaaaaaaa",    // 21 位，超出 20 位上限
            "_leading",                 // 不以字母数字开头
            "-leading",
            "has space",
            "has.dot",
            "has@at",
            "中文用户名",                // 只允许 ASCII
            "аdmin",                    // 首字母是西里尔字母 а，同形异码
    })
    void 拒绝不合规的用户名(String username) {
        assertThatThrownBy(() -> userService.createUser(username, null, VALID_PASSWORD))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "ADMIN", "Root", "system", "api", "login", "settings"})
    void 拒绝保留用户名(String username) {
        assertThatThrownBy(() -> userService.createUser(username, null, VALID_PASSWORD))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("系统保留");
    }

    /**
     * 保留字里的 "me" / "self" 长度不足 3 位，先被长度规则拦下，
     * 走不到保留字分支。结果一样是拒绝，只是文案不同。
     */
    @Test
    void 两位保留字被长度规则先行拦截() {
        assertThatThrownBy(() -> userService.createUser("me", null, VALID_PASSWORD))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("3-20 位");
    }

    /** 全角 ａｄｍｉｎ 经 NFKC 归一化后就是 admin，不能靠换宽度绕过保留字 */
    @Test
    void 全角用户名归一化后仍被保留字拦截() {
        assertThatThrownBy(() -> userService.createUser("ａｄｍｉｎ", null, VALID_PASSWORD))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("系统保留");
    }

    @Test
    void 用户名首尾空白被去掉() {
        assertThat(userService.createUser("  laoli  ", null, VALID_PASSWORD).username())
                .isEqualTo("laoli");
    }

    @Test
    void 用户名重复返回409() {
        userService.createUser("laoli", null, VALID_PASSWORD);

        assertThatThrownBy(() -> userService.createUser("laoli", null, "Another!Pass9"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * 唯一性是大小写不敏感的（schema 里 username 带 COLLATE NOCASE）。
     * 不这么做的话，laoli 和 LaoLi 会是两个视觉上难以区分的账号，
     * 需求第 8 条的"用户名唯一"就形同虚设。
     */
    @Test
    void 用户名唯一性忽略大小写() {
        userService.createUser("laoli", null, VALID_PASSWORD);

        assertThatThrownBy(() -> userService.createUser("LaoLi", null, VALID_PASSWORD))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已被使用");
    }

    /** 大小写不敏感必须贯穿到查询侧，否则登录时会找不到自己刚建的账号 */
    @Test
    void 大小写不同也能查到同一个账号() {
        long id = userService.createUser("laoli", null, VALID_PASSWORD).id();

        assertThat(userRepository.findByUsername("LAOLI"))
                .get()
                .extracting(UserAccount::id)
                .isEqualTo(id);
    }

    // ── 密码策略 ────────────────────────────────────────────────────────

    @Test
    void 拒绝过短密码() {
        assertThatThrownBy(() -> userService.createUser("laoli", null, "Ab3!45678"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("至少 10 位");
    }

    @Test
    void 拒绝空密码() {
        assertThatThrownBy(() -> userService.createUser("laoli", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("至少 10 位");
    }

    /**
     * 上限不是洁癖：bcrypt 只吃前 72 字节，多的被静默丢弃。
     * 与其让用户以为长密码生效了，不如当场拒绝。
     */
    @Test
    void 拒绝超过七十二字节的密码() {
        assertThatThrownBy(() -> userService.createUser("laoli", null, "Aa1!bC2@" + "x".repeat(70)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("密码太长");
    }

    @Test
    void 刚好七十二字节的密码被接受() {
        String password = "Aa1!bC2@dE3#" + "x".repeat(60);
        assertThat(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(72);
        assertThat(userService.createUser("laoli", null, password)).isNotNull();
    }

    /** 汉字一个占 3 字节，按字符数算会在编码层被悄悄截断 */
    @Test
    void 密码长度按UTF8字节数而不是字符数计算() {
        String password = "拾光相册密码1!" + "好".repeat(20);  // 27 字符，但 79 字节
        assertThat(password.length()).isLessThan(72);
        assertThat(password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isGreaterThan(72);
        assertThatThrownBy(() -> userService.createUser("laoli", null, password))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("密码太长");
    }

    @ParameterizedTest
    @ValueSource(strings = {"password123", "12345678901", "PASSWORD123", "iloveyou", "woaini1314"})
    void 拒绝常见弱口令(String password) {
        assertThatThrownBy(() -> userService.createUser("laoli", null, password))
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abcdefghijkl",     // 纯字母
            "1234509876543",    // 纯数字
            "!!!!!!!!!!!!",     // 纯符号
    })
    void 拒绝只有一类字符的密码(String password) {
        assertThatThrownBy(() -> userService.createUser("laoli", null, password))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("至少两类");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Adminadmin1",   // 常见词拼两遍再补数字，长度和字符种类都达标
            "admin1234567",  // 剔掉 admin 和数字串后什么也不剩
            "Password!123",  // 精确匹配表里没有它，但内容全是烂大街的词
            "aaaaaaaaa1",    // "aaa" 也在弱词表里，剔完只剩一个 1
    })
    void 拒绝由常见词拼出来的密码(String password) {
        assertThatThrownBy(() -> userService.createUser("xiaozhang", null, password))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("常见词太多");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Ab1!ab1!ab1!",  // 大小写在小写化后合并，实际只有 a/b/1/! 四种
            "Xy9@xy9@xy9@",  // 同理，看着 12 位其实只在四个字符间打转
    })
    void 拒绝字符种类太少的密码(String password) {
        assertThatThrownBy(() -> userService.createUser("xiaozhang", null, password))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不同的字符");
    }

    @Test
    void 拒绝包含用户名的密码() {
        assertThatThrownBy(() -> userService.createUser("laoli", null, "myLaoLi12345"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能包含用户名");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Yunhe!Qiao2026",
            "correct horse 9",  // 长口令，字母+数字+空格
            "MeiPass!2026",     // 含 "pass" 但剔掉后还剩 8 位，不该误杀
    })
    void 接受合规密码(String password) {
        assertThat(userService.createUser("xiaozhang", null, password)).isNotNull();
    }

    // ── 失败后不留残渣 ──────────────────────────────────────────────────

    @Test
    void 密码不合规时不写入用户() {
        assertThatThrownBy(() -> userService.createUser("laoli", null, "short"))
                .isInstanceOf(ApiException.class);

        assertThat(userRepository.findByUsername("laoli")).isEmpty();
        assertThat(userRepository.anyAdminExists()).isFalse();
    }
}
