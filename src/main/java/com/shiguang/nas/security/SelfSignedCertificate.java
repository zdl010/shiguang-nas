package com.shiguang.nas.security;

import com.shiguang.nas.common.FileSecurity;
import com.shiguang.nas.config.AppPaths;
import com.shiguang.nas.desktop.LanAddressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 自签 TLS 证书。
 *
 * <p>为什么必须要有：服务监听 0.0.0.0，明文 HTTP 下同一个 Wi-Fi 里任何人抓包
 * 就能拿到会话 Cookie，直接接管账号。局域网≠可信网络（想想合租、公司、咖啡馆）。
 *
 * <p>顺带解决一个实际问题：浏览器的 {@code crypto.subtle} 和剪贴板 API 只在
 * <b>安全上下文</b>下可用。走 http://192.168.x.x 时它们是 undefined，
 * 前端只能退回纯 JS 实现（见 stores/upload.ts）。上了 HTTPS 这些就都能用了。
 *
 * <p>用 JDK 自带的 {@code keytool} 生成，不引任何加密库。早先用的是
 * BouncyCastle，但为了那 9.8 MB 的 jar 一起把密码哈希从 Argon2id 换成了 bcrypt
 * （见 SecurityConfig），证书这边也就没有再留 BC 的理由了。
 *
 * <p>keytool 一定在：它属于 java.base，jlink/jpackage 裁剪出来的运行时里
 * 也带着（实测路径 {@code <runtime>/bin/keytool}）。用 java.home 定位，
 * 不依赖 PATH——打包后的机器上根本不会装 JDK。
 */
public final class SelfSignedCertificate {

    private static final Logger log = LoggerFactory.getLogger(SelfSignedCertificate.class);

    private static final String KEYSTORE_FILE = "tls-keystore.p12";
    private static final String ALIAS = "shiguang";

    /**
     * 有效期 825 天。
     *
     * <p><b>这个数字不能往上调。</b>自 iOS 13 / macOS 10.15 起，Apple 会拒绝
     * 任何有效期超过 825 天的 TLS 服务端证书——即使用户已经手动把它装进
     * 信任列表也没用，Safari 会直接报"证书不符合标准"并拒绝连接。
     * 而 iPhone 正是这个产品的主要客户端。
     *
     * <p>代价是每两年多要重新签一次，由 {@link #ensure} 在临期时自动完成。
     */
    private static final Duration VALIDITY = Duration.ofDays(825);

    /** 剩余有效期少于这个值就提前重签，免得哪天突然连不上 */
    private static final Duration RENEW_BEFORE = Duration.ofDays(30);

    private SelfSignedCertificate() {
    }

    public record Keystore(Path path, String password) {
    }

    /**
     * 确保 keystore 存在，不存在就生成一个。
     *
     * <p>证书的 SAN 里会写入所有本机地址。<b>这一步很关键</b>：现代浏览器
     * 完全忽略 CN，只看 SAN；SAN 里没有用户输入的那个 IP 就一定报错，
     * 而且是"不安全"那种没法点继续的报错。
     */
    public static Keystore ensure(AppPaths paths, LanAddressService lanAddressService) {
        Path keystorePath = paths.configDir().resolve(KEYSTORE_FILE);
        Path passwordPath = paths.configDir().resolve("tls-keystore.pass");

        try {
            if (Files.exists(keystorePath) && Files.exists(passwordPath)) {
                String existingPassword = Files.readString(passwordPath).strip();
                Keystore existing = new Keystore(keystorePath, existingPassword);
                if (stillValid(existing)) {
                    return existing;
                }
                // 证书临期或已过期就重签。不处理的话，某天服务会毫无预兆地
                // 变成"连不上"，而日志里只有一个含糊的 SSL 错误。
                log.info("TLS 证书已过期或即将过期，重新签发");
                deleteQuietly(keystorePath);
                deleteQuietly(passwordPath);
            }

            String password = randomPassword();
            Files.deleteIfExists(keystorePath);
            generateWithKeytool(keystorePath, password, collectNames(lanAddressService));
            FileSecurity.hardenFile(keystorePath);

            Files.deleteIfExists(passwordPath);
            Files.writeString(passwordPath, password);
            FileSecurity.hardenFile(passwordPath);

            log.info("已生成自签 TLS 证书: {}（有效期 {} 天，临期会自动重签）",
                    keystorePath, VALIDITY.toDays());
            log.info("首次访问浏览器会警告证书不受信任，这是自签证书的正常现象。");
            return new Keystore(keystorePath, password);
        } catch (Exception e) {
            throw new IllegalStateException("生成 TLS 证书失败: " + e.getMessage(), e);
        }
    }

    /** 证书是否还在有效期内且不临期。 */
    private static boolean stillValid(Keystore keystore) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(keystore.path())) {
                store.load(in, keystore.password().toCharArray());
            }
            X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
            if (certificate == null) {
                return false;
            }
            return certificate.getNotAfter().toInstant().isAfter(Instant.now().plus(RENEW_BEFORE));
        } catch (Exception e) {
            log.warn("读取现有 TLS 证书失败，将重新生成: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 收集要写进 SAN 的名字，输出 keytool {@code -ext san=} 需要的格式。
     *
     * <p>形如 {@code dns:localhost,ip:127.0.0.1,ip:192.168.1.5}。
     */
    private static String collectNames(LanAddressService lanAddressService) {
        List<String> names = new ArrayList<>();
        names.add("dns:localhost");
        names.add("ip:127.0.0.1");

        for (String url : lanAddressService.lanUrls()) {
            String host = hostOf(url);
            if (host != null && !host.isBlank()) {
                names.add("ip:" + host);
            }
        }
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            if (hostname != null && !hostname.isBlank()) {
                names.add("dns:" + hostname);
                // macOS / Linux 上局域网里常用 <主机名>.local 访问。
                // macOS 返回的主机名本身就带 .local，不判断会拼出 xxx.local.local
                if (!hostname.toLowerCase(java.util.Locale.ROOT).endsWith(".local")) {
                    names.add("dns:" + hostname + ".local");
                }
            }
        } catch (Exception e) {
            log.debug("无法获取主机名: {}", e.getMessage());
        }
        return names.stream().distinct().collect(java.util.stream.Collectors.joining(","));
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** keytool 的绝对路径。走 java.home 而不是 PATH——目标机器上不会装 JDK。 */
    private static Path keytoolPath() {
        String exe = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "keytool.exe" : "keytool";
        Path path = Path.of(System.getProperty("java.home"), "bin", exe);
        if (!Files.isExecutable(path)) {
            throw new IllegalStateException("找不到 keytool: " + path);
        }
        return path;
    }

    /**
     * 调 keytool 生成一个只含自签证书的 PKCS12。
     *
     * <p>DN 里刻意<b>只用 ASCII</b>。keytool 是按平台默认编码解析命令行参数的，
     * Windows 上中文 DN 会变成乱码甚至让命令失败。反正现代浏览器完全忽略 CN，
     * 只看 SAN，这里写什么都不影响能不能连上。
     *
     * <p>密码通过 stdin 喂给它（{@code -storepass:env} 在部分平台不可用，
     * 而写在命令行上会出现在 ps 输出里，同机器的其他用户就能看到）。
     */
    private static void generateWithKeytool(Path keystorePath, String password, String san)
            throws Exception {
        List<String> command = List.of(
                keytoolPath().toString(),
                "-genkeypair",
                "-alias", ALIAS,
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                // EC P-256 而不是 RSA-2048：同等强度下密钥和握手都小得多，
                // 手机在弱 Wi-Fi 下建连明显更快，且所有现代浏览器都支持。
                "-keyalg", "EC",
                "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA",
                "-validity", String.valueOf(VALIDITY.toDays()),
                "-dname", "CN=Shiguang NAS, O=Shiguang",
                "-ext", "san=" + san,
                "-ext", "bc:critical=ca:false",
                "-ext", "ku:critical=digitalSignature,keyEncipherment",
                "-ext", "eku=serverAuth");
                // 刻意不传 -storepass：keytool 只接受明文参数，那会让口令出现在
                // ps 的输出里，同机器上任何用户都能看到。省掉它，keytool 就改从
                // stdin 提示读——见下面往它 stdin 里写的那几行。

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        try (var stdin = process.getOutputStream()) {
            // 它会问"输入密钥库口令"和"再次输入新口令"两次。多写一行是保险：
            // 万一某个版本还要问一次密钥口令（PKCS12 下必须与库口令相同），
            // 也能答上；多余的行在我们关掉 stdin 后被丢弃，没有副作用。
            stdin.write((password + System.lineSeparator()).repeat(3)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("keytool 超时未返回");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("keytool 生成证书失败（退出码 "
                    + process.exitValue() + "）: " + output.strip());
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 导出证书（不含私钥），供用户装到手机上以消除浏览器警告。 */
    public static void exportCertificate(Keystore keystore, Path target) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore.path())) {
            store.load(in, keystore.password().toCharArray());
        }
        X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + java.util.Base64.getMimeEncoder(64, new byte[]{'\n'})
                        .encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        Files.writeString(target, pem);
    }

    static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("删除失败 {}: {}", path, e.getMessage());
        }
    }
}
