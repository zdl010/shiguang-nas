package com.shiguang.nas.desktop;

import com.shiguang.nas.config.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;

/**
 * 启动完成后的收尾动作：打印访问地址、挂托盘、自动开浏览器。
 * 这几件事合起来就是需求第 6 条"双击部署"的实际体感。
 */
@Component
public class StartupRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    private final LanAddressService lanAddressService;
    private final TrayService trayService;
    private final AppPaths appPaths;
    private final boolean openBrowser;

    public StartupRunner(LanAddressService lanAddressService,
                         TrayService trayService,
                         AppPaths appPaths,
                         @Value("${shiguang.open-browser:true}") boolean openBrowser) {
        this.lanAddressService = lanAddressService;
        this.trayService = trayService;
        this.appPaths = appPaths;
        this.openBrowser = openBrowser;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        printBanner();
        trayService.install();
        if (openBrowser) {
            openBrowser(lanAddressService.localUrl());
        }
    }

    private void printBanner() {
        List<String> lanUrls = lanAddressService.lanUrls();
        log.info("");
        log.info("  拾光 NAS 已启动");
        log.info("  ├─ 本机访问   {}", lanAddressService.localUrl());
        if (lanUrls.isEmpty()) {
            log.info("  ├─ 局域网访问 未检测到局域网地址，请检查网络连接");
        } else {
            for (int i = 0; i < lanUrls.size(); i++) {
                log.info("  ├─ 局域网访问 {}{}", lanUrls.get(i), i == 0 ? "   ← 手机用这个" : "");
            }
        }
        log.info("  ├─ 存储目录   {}", appPaths.storageRoot());
        log.info("  └─ 配置目录   {}", appPaths.configDir());
        log.info("");
        log.info("  提示：Windows 首次启动会弹防火墙提示，必须勾选\"专用网络\"，");
        log.info("       否则手机无法访问。");
        log.info("");
    }

    private void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            // 无头服务器、没有默认浏览器等都会走到这里，属于正常情况
            log.debug("自动打开浏览器失败，请手动访问 {}: {}", url, e.getMessage());
        }
    }
}
