package com.shiguang.nas.security;

import com.shiguang.nas.common.FileSecurity;
import com.shiguang.nas.config.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 实例级密钥。首次启动用 {@link SecureRandom} 生成，落盘为仅属主可读的文件。
 *
 * <p>目前用于给媒体访问签发短时效签名 URL。签名 URL 的作用是：
 * {@code <img src>} / {@code <video src>} 这类标签无法附加自定义鉴权头，
 * 而直接依赖会话 Cookie 又会让链接被复制出去后仍然有效。签名 URL 让每个媒体地址
 * 都绑定用户 + 过期时间，既能鉴权又不破坏 HTTP Range 请求。
 *
 * <p><b>密钥永远不出这台机器</b>：不上报、不写日志、不进任何接口响应。
 */
public final class InstanceSecrets {

    private static final Logger log = LoggerFactory.getLogger(InstanceSecrets.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int KEY_BYTES = 32;

    private final byte[] mediaUrlKey;

    private InstanceSecrets(byte[] mediaUrlKey) {
        this.mediaUrlKey = mediaUrlKey;
    }

    public static InstanceSecrets loadOrCreate(AppPaths paths) {
        Path keyFile = paths.secretKeyFile();
        try {
            if (Files.exists(keyFile)) {
                byte[] key = Base64.getDecoder()
                        .decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
                if (key.length < KEY_BYTES) {
                    throw new IllegalStateException(
                            "密钥文件已损坏（长度不足），请删除后重启以重新生成: " + keyFile);
                }
                // 每次启动都重新加固一遍，防止用户手工拷贝后权限被放开
                FileSecurity.hardenFile(keyFile);
                return new InstanceSecrets(key);
            }

            byte[] key = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(key);
            // 先建文件再写内容，确保权限在数据落盘前就已收紧
            Files.createFile(keyFile);
            FileSecurity.hardenFile(keyFile);
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key),
                    StandardCharsets.US_ASCII);
            FileSecurity.hardenFile(keyFile);
            log.info("已生成实例密钥: {}（请随数据库一起备份，丢失后所有已发出的媒体链接将失效）", keyFile);
            return new InstanceSecrets(key);
        } catch (IOException e) {
            throw new UncheckedIOException("读写实例密钥失败: " + keyFile, e);
        }
    }

    /** 对任意载荷做 HMAC-SHA256，返回 URL-safe base64（无填充）。 */
    public String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(mediaUrlKey, HMAC_ALGORITHM));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /** 恒定时间比对，避免通过响应耗时逐字节爆破签名。 */
    public boolean verify(String payload, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(payload).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }
}
