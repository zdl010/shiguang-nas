package com.shiguang.nas.config;

import com.shiguang.nas.common.FileSecurity;
import com.shiguang.nas.common.Platform;
import com.shiguang.nas.storage.StorageRootResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 两套目录，职责分开：
 *
 * <ul>
 *   <li><b>配置目录</b>（小，跟随用户账户）：config.properties、secret.key、setup-token.txt
 *   <li><b>存储根目录</b>（大，在剩余空间最大的盘）：媒体原文件、缩略图、SQLite 数据库、日志
 * </ul>
 *
 * <p>分开的原因：存储根目录可能在移动硬盘上被拔掉，而配置和密钥必须始终可读，
 * 否则重新插上盘之后签名 URL 的密钥变了，所有已发出的链接全部失效。
 */
public final class AppPaths {

    private static final Logger log = LoggerFactory.getLogger(AppPaths.class);

    private static final String CONFIG_FILE = "config.properties";
    private static final String KEY_STORAGE_ROOT = "storage.root";

    private final Path configDir;
    private final Path storageRoot;

    private AppPaths(Path configDir, Path storageRoot) {
        this.configDir = configDir;
        this.storageRoot = storageRoot;
    }

    /**
     * 初始化目录结构。首次运行会探测存储位置并写入配置固化；
     * 之后每次启动都读配置，不再重新探测。
     */
    public static AppPaths initialize() {
        try {
            Path configDir = FileSecurity.createSecureDirectory(defaultConfigDir());
            Path configFile = configDir.resolve(CONFIG_FILE);

            Properties props = load(configFile);
            String configured = props.getProperty(KEY_STORAGE_ROOT);

            Path storageRoot;
            if (configured != null && !configured.isBlank()) {
                storageRoot = Path.of(configured.trim());
                log.info("存储根目录（来自配置）: {}", storageRoot);
            } else {
                storageRoot = StorageRootResolver.resolveDefault();
                log.info("首次启动，自动选定存储根目录: {}", storageRoot);
            }

            AppPaths paths = new AppPaths(configDir, storageRoot);
            paths.createStorageLayout();

            // 只有目录真的创建成功才固化，避免把一个建不出来的路径写死进配置
            if (configured == null || configured.isBlank()) {
                props.setProperty(KEY_STORAGE_ROOT, storageRoot.toAbsolutePath().toString());
                save(configFile, props);
            }
            return paths;
        } catch (IOException e) {
            throw new UncheckedIOException("初始化数据目录失败", e);
        }
    }

    private void createStorageLayout() throws IOException {
        FileSecurity.createSecureDirectory(storageRoot);
        FileSecurity.createSecureDirectory(mediaDir());
        FileSecurity.createSecureDirectory(thumbDir());
        FileSecurity.createSecureDirectory(tempDir());
        FileSecurity.createSecureDirectory(databaseDir());
        FileSecurity.createSecureDirectory(logDir());
    }

    public Path configDir()   { return configDir; }
    public Path storageRoot() { return storageRoot; }

    /** 媒体原文件，内容寻址：media/ab/cd/&lt;sha256&gt;.jpg */
    public Path mediaDir()    { return storageRoot.resolve("media"); }
    /** 缩略图与视频封面帧 */
    public Path thumbDir()    { return storageRoot.resolve("thumb"); }
    /** 分片上传的临时落盘区 */
    public Path tempDir()     { return storageRoot.resolve("temp"); }
    public Path databaseDir() { return storageRoot.resolve("db"); }
    public Path logDir()      { return storageRoot.resolve("logs"); }

    public Path databaseFile() { return databaseDir().resolve("shiguang.db"); }
    public Path secretKeyFile() { return configDir.resolve("secret.key"); }

    /**
     * 配置文件里写着的根目录。
     *
     * <p>它可能和 {@link #storageRoot()} 不同——后者是<b>本进程正在用</b>的目录，
     * 改配置后要重启才会一致。界面上必须把两者都显示出来，否则用户改完看到
     * 还是旧路径，只会以为"没保存上"。
     */
    public Path configuredStorageRoot() {
        try {
            Properties props = load(configDir.resolve(CONFIG_FILE));
            String value = props.getProperty(KEY_STORAGE_ROOT);
            return value == null || value.isBlank() ? storageRoot : Path.of(value.trim());
        } catch (IOException e) {
            return storageRoot;
        }
    }

    /**
     * 改存储根目录。只写配置，<b>不搬运已有文件、也不改本进程正在用的路径</b>。
     *
     * <p>为什么不当场生效：数据库文件是打开着的，媒体目录里可能正有分片在写。
     * 运行时切换要么得停掉所有 IO，要么就会留下一半在旧目录、一半在新目录的烂摊子。
     * 重启一次是几秒钟的事，换来的是一个绝不会写坏数据的实现。
     *
     * <p>为什么不自动搬运：可能是几百 GB 跨盘复制，中途断电就毁了。让用户自己用
     * 系统的文件管理器搬，他能看到进度、能中断、能验证。
     *
     * @return 校验后的绝对路径
     */
    public Path updateStorageRoot(Path candidate) throws IOException {
        Path target = candidate.toAbsolutePath().normalize();

        if (Files.exists(target)) {
            if (!Files.isDirectory(target)) {
                throw new IOException("这个路径是一个文件，不是目录");
            }
        } else {
            // 试着建出来。建不出来通常是权限或盘符不存在，这时候就该报错而不是存下去
            Files.createDirectories(target);
        }
        if (!Files.isWritable(target)) {
            throw new IOException("目录不可写，请检查权限");
        }

        // 真写一个文件试试。isWritable 在某些网络盘和只读挂载上会撒谎
        Path probe = target.resolve(".shiguang-write-test");
        try {
            Files.writeString(probe, "ok");
        } finally {
            Files.deleteIfExists(probe);
        }

        Path configFile = configDir.resolve(CONFIG_FILE);
        Properties props = load(configFile);
        props.setProperty(KEY_STORAGE_ROOT, target.toString());
        save(configFile, props);
        log.info("存储根目录已改为 {}，重启后生效", target);
        return target;
    }

    private static Path defaultConfigDir() {
        if (Platform.isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData, "ShiguangNAS");
            }
            return userHome().resolve("AppData/Local/ShiguangNAS");
        }
        if (Platform.isMac()) {
            return userHome().resolve("Library/Application Support/ShiguangNAS");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "shiguang-nas");
        }
        return userHome().resolve(".config/shiguang-nas");
    }

    private static Path userHome() {
        return Path.of(System.getProperty("user.home"));
    }

    private static Properties load(Path file) throws IOException {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(in);
            }
        }
        return props;
    }

    private static void save(Path file, Properties props) throws IOException {
        try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(out, "拾光 NAS 实例配置 —— 修改存储位置请通过设置页，手工改动需自行迁移数据");
        }
        FileSecurity.hardenFile(file);
    }
}
