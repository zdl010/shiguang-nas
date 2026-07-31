package com.shiguang.nas.media;

import com.shiguang.nas.security.InstanceSecrets;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 媒体访问的签名 URL。
 *
 * <p>为什么需要它：{@code <video src>}、{@code <img src>} 这些标签发出的请求
 * 会带 Cookie，本来靠会话就够了。但有两个场景不行：
 * <ul>
 *   <li>用户想把某个视频链接发给家人在浏览器里直接打开——那台设备没有会话
 *   <li>下载链接需要能被系统的下载器接管，而下载器不共享浏览器的 Cookie
 * </ul>
 *
 * <p>签名里绑定了 mediaId、用途和过期时间。少绑任何一项都会出问题：
 * 不绑 id 则一个签名能取任意文件；不绑用途则缩略图的签名能拿来下原图；
 * 不绑过期时间则链接一旦泄露就是永久有效。
 */
@Service
public class MediaLinkService {

    /** 签名有效期。够看完一部电影，又不至于泄露后长期可用。 */
    private static final Duration TTL = Duration.ofHours(6);

    public static final String PURPOSE_RAW = "raw";
    public static final String PURPOSE_THUMB = "thumb";
    public static final String PURPOSE_DOWNLOAD = "download";

    private final InstanceSecrets secrets;

    public MediaLinkService(InstanceSecrets secrets) {
        this.secrets = secrets;
    }

    public record SignedLink(long expiresAt, String signature) {
    }

    public SignedLink sign(long mediaId, long ownerId, String purpose) {
        long expiresAt = System.currentTimeMillis() + TTL.toMillis();
        return new SignedLink(expiresAt, secrets.sign(payload(mediaId, ownerId, purpose, expiresAt)));
    }

    public boolean verify(long mediaId, long ownerId, String purpose, long expiresAt,
                          String signature) {
        if (expiresAt < System.currentTimeMillis()) {
            return false;
        }
        return secrets.verify(payload(mediaId, ownerId, purpose, expiresAt), signature);
    }

    /**
     * 用 {@code |} 分隔而不是直接拼接。
     *
     * <p>不加分隔符的话 (12, 34) 和 (1, 234) 会拼出同一个字符串，
     * 一个媒体的签名就能拿去访问另一个——这类边界混淆是签名实现的经典坑。
     */
    private static String payload(long mediaId, long ownerId, String purpose, long expiresAt) {
        return mediaId + "|" + ownerId + "|" + purpose + "|" + expiresAt;
    }
}
