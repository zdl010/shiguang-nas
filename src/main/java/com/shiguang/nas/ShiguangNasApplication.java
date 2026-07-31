package com.shiguang.nas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// 缩略图生成、回收站清理、会话回收都靠 @Scheduled
@EnableScheduling
public class ShiguangNasApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ShiguangNasApplication.class);
        // 托盘图标需要 AWT。真正的无头服务器上 SystemTray.isSupported() 会返回 false，
        // TrayService 会安静跳过，所以这里放开 headless 不会影响 Linux 服务端部署。
        app.setHeadless(false);
        app.run(args);
    }
}
