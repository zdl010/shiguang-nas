package com.shiguang.nas.desktop;

import com.shiguang.nas.config.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.List;

/**
 * 系统托盘。对"双击部署"的观感提升很大：用户关掉浏览器窗口后，
 * 服务还在跑，托盘图标是唯一能让他找回入口、以及正常退出的地方。
 *
 * <p>所有 AWT 调用都被兜住：真正的无头 Linux 服务器上 {@code SystemTray.isSupported()}
 * 返回 false，这里安静跳过，不影响服务端部署。
 */
@Service
public class TrayService {

    private static final Logger log = LoggerFactory.getLogger(TrayService.class);

    private final LanAddressService lanAddressService;
    private final AppPaths appPaths;
    private final ApplicationContext applicationContext;
    private final boolean enabled;

    private TrayIcon trayIcon;

    public TrayService(LanAddressService lanAddressService,
                       AppPaths appPaths,
                       ApplicationContext applicationContext,
                       @Value("${shiguang.tray.enabled:true}") boolean enabled) {
        this.lanAddressService = lanAddressService;
        this.appPaths = appPaths;
        this.applicationContext = applicationContext;
        this.enabled = enabled;
    }

    public void install() {
        if (!enabled) {
            return;
        }
        try {
            if (!SystemTray.isSupported()) {
                log.debug("当前环境不支持系统托盘，跳过");
                return;
            }
            SystemTray tray = SystemTray.getSystemTray();
            PopupMenu menu = new PopupMenu();

            MenuItem open = new MenuItem("打开拾光 NAS");
            open.addActionListener(e -> browse(lanAddressService.localUrl()));
            menu.add(open);

            List<String> lanUrls = lanAddressService.lanUrls();
            if (!lanUrls.isEmpty()) {
                MenuItem address = new MenuItem("局域网地址：" + lanUrls.get(0));
                address.addActionListener(e -> browse(lanUrls.get(0)));
                menu.add(address);
            }

            MenuItem storage = new MenuItem("打开存储目录");
            storage.addActionListener(e -> openDirectory());
            menu.add(storage);

            menu.addSeparator();
            MenuItem quit = new MenuItem("退出");
            quit.addActionListener(e -> shutdown());
            menu.add(quit);

            trayIcon = new TrayIcon(createIcon(), "拾光 NAS", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> browse(lanAddressService.localUrl()));
            tray.add(trayIcon);
        } catch (Exception e) {
            log.debug("安装托盘图标失败，忽略: {}", e.getMessage());
        }
    }

    private void browse(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            log.debug("打开浏览器失败: {}", e.getMessage());
        }
    }

    private void openDirectory() {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(appPaths.storageRoot().toFile());
            }
        } catch (Exception e) {
            log.debug("打开存储目录失败: {}", e.getMessage());
        }
    }

    private void shutdown() {
        try {
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
        } catch (Exception ignored) {
            // 退出流程里图标移除失败无所谓
        }
        int code = SpringApplication.exit(applicationContext, () -> 0);
        System.exit(code);
    }

    /**
     * 托盘图标。优先用 classpath 里的 icon.png，让托盘和应用图标是同一个视觉
     * （那张图由 packaging/make-icon.py 生成）。读不到就退回代码画的简版，
     * 图标缺失不该让托盘整个挂掉。
     */
    private Image createIcon() {
        try (var in = TrayService.class.getResourceAsStream("/icon.png")) {
            if (in != null) {
                BufferedImage loaded = javax.imageio.ImageIO.read(in);
                if (loaded != null) {
                    return loaded;
                }
            }
        } catch (java.io.IOException e) {
            log.debug("读取托盘图标失败，改用内嵌绘制版本: {}", e.getMessage());
        }
        return drawFallbackIcon();
    }

    /** 兜底图标：一个圆角相机取景框，纯代码绘制，不依赖任何资源文件。 */
    private Image drawFallbackIcon() {
        int size = 64;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // 品牌色取自原型的 --a1 (#FF4D8D)
        g.setColor(new java.awt.Color(0xFF, 0x4D, 0x8D));
        g.fillRoundRect(6, 14, 52, 40, 14, 14);
        g.setColor(new java.awt.Color(0x0D, 0x09, 0x18));
        g.fillOval(22, 24, 20, 20);
        g.setColor(new java.awt.Color(0xFF, 0xC2, 0x4B));
        g.fillOval(27, 29, 10, 10);
        g.dispose();
        return image;
    }
}
