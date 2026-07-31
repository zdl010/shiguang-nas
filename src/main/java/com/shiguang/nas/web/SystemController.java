package com.shiguang.nas.web;

import com.shiguang.nas.config.AppPaths;
import com.shiguang.nas.desktop.LanAddressService;
import com.shiguang.nas.security.SelfSignedCertificate;
import com.shiguang.nas.user.UserAccount;
import com.shiguang.nas.user.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final LanAddressService lanAddressService;
    private final AppPaths appPaths;
    private final UserRepository userRepository;
    private final String version;
    private final boolean tlsEnabled;

    public SystemController(LanAddressService lanAddressService,
                            AppPaths appPaths,
                            UserRepository userRepository,
                            @Value("${shiguang.version:0.1.0}") String version,
                            @Value("${shiguang.tls.enabled:false}") boolean tlsEnabled) {
        this.lanAddressService = lanAddressService;
        this.appPaths = appPaths;
        this.userRepository = userRepository;
        this.version = version;
        this.tlsEnabled = tlsEnabled;
    }

    /**
     * 未登录即可访问的最小信息集，前端用它决定该显示"初始化"还是"登录"页。
     *
     * <p><b>刻意不返回</b>：用户数量、用户名列表、存储路径、磁盘剩余空间、
     * 初始化令牌。这些对未认证访问者都是情报。局域网地址是例外——
     * 能访问到这个接口的人本来就已经知道该地址了。
     */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "拾光 NAS");
        result.put("version", version);
        result.put("lanUrls", lanAddressService.lanUrls());
        result.put("tls", tlsEnabled);

        // 管理员还在用初始密码时告诉前端，让登录页把初始账号密码显示出来。
        //
        // 这不算泄密：初始密码是写在 README 和启动日志里的公开常量，知道这个产品的人
        // 本来就知道。而不显示的代价是实打实的——双击启动的用户看不到控制台，
        // 界面上又只字不提，他会以为自己密码记错了，试几次还会被登录限流挡住。
        result.put("needsInitialPassword", needsInitialPassword());
        return result;
    }

    private boolean needsInitialPassword() {
        return userRepository.findByUsername(UserAccount.ADMIN_USERNAME)
                .map(UserAccount::mustChangePassword)
                .orElse(false);
    }

    /**
     * 下载自签证书（PEM，不含私钥），装到手机上就不会再弹证书警告。
     *
     * <p>要求已登录：证书本身不是机密（它就是要公开分发的），但这个接口
     * 会确认服务确实开着 TLS，没必要对未认证访问者暴露这个信息。
     */
    @GetMapping("/certificate")
    public void certificate(HttpServletResponse response) throws Exception {
        if (!tlsEnabled) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        var keystore = SelfSignedCertificate.ensure(appPaths, lanAddressService);
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("shiguang-cert", ".pem");
        try {
            SelfSignedCertificate.exportCertificate(keystore, temp);
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader("Content-Disposition", "attachment; filename=\"shiguang-nas.pem\"");
            response.getOutputStream().write(java.nio.file.Files.readAllBytes(temp));
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
        }
    }
}
