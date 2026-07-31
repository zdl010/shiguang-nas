package com.shiguang.nas.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 开机自动创建唯一管理员 {@code admin}。
 *
 * <p>取代了之前"第一个注册的账号即管理员"的模型。那个模型有个抢注窗口：
 * 谁先打开页面谁就是管理员。现在开机就有 admin，机主不需要抢。
 *
 * <p><b>初始密码是一个公开常量</b>，因此从首次启动到管理员改密之间，
 * 同局域网的人也能用它登进来。缓解手段是强制改密网关
 * （见 {@code MustChangePasswordFilter}）：在密码被改掉之前，
 * 除了"改密码"这一个接口，其余 API 一律 403 —— 闯进来的人既看不到照片，
 * 也改不了任何设置，而机主只要先登录一次就能把密码定死。
 *
 * <p>所以正确的部署顺序是：<b>先启动、先登录改密，再接入局域网。</b>
 */
@Component
public class AdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    /**
     * 初始密码。
     *
     * <p>刻意不用随机值：随机值就得打印到控制台让用户去抄，那正是之前被去掉的
     * "初始化令牌"体验。一个所有人都猜得到的默认值 + 强制改密，是这类设备的通行做法
     * （路由器、NAS 都是这么干的）。
     */
    private static final String INITIAL_PASSWORD = "admin";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.findByUsername(UserAccount.ADMIN_USERNAME).isPresent()) {
            warnIfStillDefault();
            return;
        }

        userRepository.insertBootstrapAdmin(
                UserAccount.ADMIN_USERNAME, "管理员", passwordEncoder.encode(INITIAL_PASSWORD));

        log.warn("");
        log.warn("╔══════════════════════════════════════════════════════════════╗");
        log.warn("║  已创建管理员账号                                            ║");
        log.warn("╚══════════════════════════════════════════════════════════════╝");
        log.warn("  用户名：{}", UserAccount.ADMIN_USERNAME);
        log.warn("  初始密码：{}", INITIAL_PASSWORD);
        log.warn("");
        log.warn("  这个密码是公开的，登录后会强制要求你改成一个复杂密码。");
        log.warn("  在改掉之前，同局域网的其他人也能用它登录——所以请**先改密码再接入局域网**。");
        log.warn("");
    }

    /** 每次启动都检查一下，密码还没改就继续提醒——这是个真实的敞口，不该只说一次。 */
    private void warnIfStillDefault() {
        userRepository.findByUsername(UserAccount.ADMIN_USERNAME)
                .filter(UserAccount::mustChangePassword)
                .ifPresent(admin -> {
                    log.warn("");
                    log.warn("  ⚠ 管理员仍在使用初始密码（{} / {}），任何人都能登进来。",
                            UserAccount.ADMIN_USERNAME, INITIAL_PASSWORD);
                    log.warn("    登录后会强制要求修改，请尽快完成。");
                    log.warn("");
                });
    }
}
