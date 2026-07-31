package com.shiguang.nas.config;

import com.shiguang.nas.desktop.LanAddressService;
import com.shiguang.nas.security.SelfSignedCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自签 TLS。
 *
 * <p><b>默认关闭</b>，用 {@code --shiguang.tls.enabled=true} 打开。
 *
 * <p>为什么不默认开：自签证书会让浏览器弹一个红色的"你的连接不是私密连接"，
 * 手机上还要点两三层"高级 → 继续前往"。对一个主打"双击就能用"的产品来说，
 * 这个首屏体验的代价太大，应该由用户在知情后主动选择。
 *
 * <p>但强烈建议在共享网络（合租、公司、宿舍）里打开。不加密的话，
 * 同一个 Wi-Fi 下抓一个包就能拿到会话 Cookie 直接接管账号。
 * 开启后前端还能用上 {@code crypto.subtle} 和剪贴板 API（它们只在安全上下文可用）。
 */
@Configuration
@ConditionalOnProperty(name = "shiguang.tls.enabled", havingValue = "true")
public class TlsConfig {

    private static final Logger log = LoggerFactory.getLogger(TlsConfig.class);

    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> tlsCustomizer(
            AppPaths appPaths, LanAddressService lanAddressService) {
        return factory -> {
            SelfSignedCertificate.Keystore keystore =
                    SelfSignedCertificate.ensure(appPaths, lanAddressService);

            Ssl ssl = new Ssl();
            ssl.setEnabled(true);
            ssl.setKeyStore(keystore.path().toAbsolutePath().toString());
            ssl.setKeyStorePassword(keystore.password());
            ssl.setKeyStoreType("PKCS12");
            ssl.setKeyAlias("shiguang");
            // 只留 TLS 1.2/1.3。1.0/1.1 早已不安全，而且没有任何还在用的浏览器需要它们。
            ssl.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            factory.setSsl(ssl);

            log.info("已启用 HTTPS。首次访问会提示证书不受信任——");
            log.info("这是自签证书的正常表现，在设置页可以下载证书装到手机上以消除警告。");
        };
    }
}
