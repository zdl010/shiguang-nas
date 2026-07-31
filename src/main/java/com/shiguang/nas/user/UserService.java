package com.shiguang.nas.user;

import com.shiguang.nas.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class UserService {

    /**
     * 用户名字符白名单。只允许 ASCII 字母、数字、下划线、连字符，且必须以字母数字开头。
     *
     * <p>不放开 Unicode 是刻意的：同形异码字符（如西里尔字母 а 和拉丁字母 a）会让
     * 攻击者注册出肉眼无法与管理员区分的账号。需求第 8 条要求用户名唯一，
     * 而"唯一"必须包含"视觉上可区分"，否则唯一性形同虚设。
     */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{2,19}$");

    /** 会与系统语义冲突或容易被用来钓鱼的用户名 */
    private static final Set<String> RESERVED_USERNAMES = Set.of(
            "admin", "administrator", "root", "system", "shiguang", "拾光",
            "api", "static", "assets", "login", "logout", "setup", "settings",
            "null", "undefined", "anonymous", "me", "self");

    private static final int PASSWORD_MIN_LENGTH = 10;
    /**
     * 密码长度上限，按 <b>UTF-8 字节数</b>算。
     *
     * <p>72 不是随便定的：bcrypt 的输入就是 72 字节，多出来的部分被静默丢弃。
     * 如果放到 128 字符，用户设一个长密码会"看起来生效了"，实际只有开头一截
     * 参与哈希，而他永远不会知道。宁可当场拒绝，也不要沉默地削弱。
     *
     * <p>按字节而不是字符：一个汉字占 3 字节，24 个汉字就到顶了，
     * 按字符数算的话会在编码层被截断。
     */
    private static final int PASSWORD_MAX_BYTES = 72;

    /** 最常见的弱口令。不引第三方字典库，覆盖高频的那一小撮即可。 */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "password", "password1", "password123", "12345678", "123456789", "1234567890",
            "qwertyuiop", "abc123456", "iloveyou", "admin123", "shiguang", "shiguangnas",
            "11111111", "00000000", "88888888", "woaini1314", "a1234567", "1qaz2wsx");

    /**
     * 会被"剔掉再量长度"的常见词。
     *
     * <p>顺序有讲究：长的放前面。先剔 "password" 再剔 "pass"，否则 "pass" 会把
     * "password" 咬掉一半，剩下的 "word" 反而被当成有效长度算进去。
     */
    private static final List<String> WEAK_TOKENS = List.of(
            "shiguangnas", "iloveyou", "password", "woaini", "qwerty", "shiguang",
            "1234567890", "123456789", "12345678", "1234567", "123456",
            "admin", "root", "login", "user", "pass", "nas", "abc", "aaa", "000", "111");

    /**
     * 剔掉常见词之后，剩下的部分至少还要有这么长。
     *
     * <p>不能拿完整的 10 位去要求：正常人写的 "MeiPass!2026" 也含 "pass"，
     * 剔完剩 8 位，那样会把一堆好密码一并误杀。6 位是"还有点真东西"的下限。
     */
    private static final int MIN_RESIDUAL_LENGTH = 6;

    /** 密码里至少要出现这么多种不同字符，挡掉 "aaaaaaaaab1" 这类凑长度的写法 */
    private static final int MIN_DISTINCT_CHARS = 5;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserAccount createAdmin(String username, String displayName, String rawPassword) {
        return create(username, displayName, rawPassword, UserAccount.ROLE_ADMIN);
    }

    @Transactional
    public UserAccount createUser(String username, String displayName, String rawPassword) {
        return create(username, displayName, rawPassword, UserAccount.ROLE_USER);
    }

    private UserAccount create(String username, String displayName, String rawPassword, String role) {
        String normalized = normalizeUsername(username);
        validateUsername(normalized);
        validatePassword(rawPassword, normalized);

        if (userRepository.existsByUsername(normalized)) {
            throw new ApiException(HttpStatus.CONFLICT, "用户名已被使用");
        }

        String label = (displayName == null || displayName.isBlank())
                ? normalized
                : displayName.strip();
        if (label.length() > 32) {
            label = label.substring(0, 32);
        }

        long id = userRepository.insert(normalized, label, passwordEncoder.encode(rawPassword), role);
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("用户创建后无法读回: " + normalized));
    }

    /**
     * 改密码。要求提供当前密码——只靠会话就能改密的话，一次会话劫持
     * 就等于永久接管账号。
     */
    @Transactional
    public void changePassword(UserAccount user, String currentPassword, String newPassword) {
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "当前密码不正确");
        }
        validatePassword(newPassword, user.username());
        if (passwordEncoder.matches(newPassword, user.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "新密码不能和当前密码相同");
        }
        userRepository.updatePassword(user.id(), passwordEncoder.encode(newPassword));
    }

    /**
     * 管理员重置他人密码。
     *
     * <p>与 {@link #changePassword} 的区别是不校验旧密码——管理员本来就没有。
     * 但密码强度规则一视同仁：管理员图省事设一个弱口令，被爆破的是用户的账号。
     */
    @Transactional
    public void resetPassword(UserAccount target, String newPassword) {
        validatePassword(newPassword, target.username());
        userRepository.updatePassword(target.id(), passwordEncoder.encode(newPassword));
    }

    @Transactional
    public String updateDisplayName(long userId, String displayName) {
        String label = displayName == null ? "" : displayName.strip();
        if (label.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "显示名不能为空");
        }
        if (label.length() > 32) {
            label = label.substring(0, 32);
        }
        userRepository.updateDisplayName(userId, label);
        return label;
    }

    /** 校验密码是否匹配，供两步验证关闭等需要二次确认身份的场景使用。 */
    public boolean passwordMatches(UserAccount user, String rawPassword) {
        return rawPassword != null && passwordEncoder.matches(rawPassword, user.passwordHash());
    }

    /**
     * 归一化用户名。NFKC 会把全角字符、连字等折叠成标准形式，
     * 防止用「ａｄｍｉｎ」（全角）绕过保留字检查。
     */
    public static String normalizeUsername(String raw) {
        if (raw == null) {
            return "";
        }
        return Normalizer.normalize(raw.strip(), Normalizer.Form.NFKC);
    }

    private void validateUsername(String username) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "用户名需为 3-20 位字母、数字、下划线或连字符，且以字母或数字开头");
        }
        if (RESERVED_USERNAMES.contains(username.toLowerCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "该用户名为系统保留，请更换");
        }
    }

    private void validatePassword(String password, String username) {
        if (password == null || password.length() < PASSWORD_MIN_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "密码至少 " + PASSWORD_MIN_LENGTH + " 位");
        }
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > PASSWORD_MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "密码太长（超过 " + PASSWORD_MAX_BYTES + " 字节，汉字算 3 字节）");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (WEAK_PASSWORDS.contains(lower)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "该密码过于常见，请更换");
        }
        if (lower.contains(username.toLowerCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码不能包含用户名");
        }
        // 先判"字符种类"再判后面那两条：纯符号的 "!!!!!!!!!!!!" 三条规则都违反，
        // 但"只有一类字符"是最贴合、最好改的说法，应该由它来报错。
        int classes = 0;
        if (password.chars().anyMatch(Character::isDigit)) classes++;
        if (password.chars().anyMatch(Character::isLetter)) classes++;
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) classes++;
        if (classes < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码需包含字母、数字、符号中的至少两类");
        }
        // 精确匹配弱口令表挡不住 "adminadmin1" 这种——它把常见词拼一遍再补个数字，
        // 长度和字符种类都达标，却正是字典攻击第一轮就会试的形状。
        // 判据：把这些烂大街的词从密码里剔掉，剩下的部分还得够长。
        String stripped = lower;
        for (String token : WEAK_TOKENS) {
            stripped = stripped.replace(token, "");
        }
        if (stripped.length() < MIN_RESIDUAL_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码里常见词太多，请换一个不好猜的");
        }
        // 在小写串上数：Aa 只算一种。"Aa1!xxxxxxxx" 看着有大小写有符号，
        // 实际重复的是同一个 x，这种凑长度的写法不该算作有多样性。
        if (lower.chars().distinct().count() < MIN_DISTINCT_CHARS) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "密码至少要用 " + MIN_DISTINCT_CHARS + " 种不同的字符");
        }
    }
}
