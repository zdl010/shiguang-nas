package com.shiguang.nas.media;

import com.shiguang.nas.common.FileSecurity;
import com.shiguang.nas.config.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 定位 ffmpeg / ffprobe 可执行文件。
 *
 * <p>二进制由 {@code org.bytedeco:ffmpeg} 提供（见 pom.xml 里选它的理由）。
 * 那个 jar 除了原生库之外还带 {@code ffmpeg} 和 {@code ffprobe} 两个可执行文件，
 * 所以这里仍然走命令行调用，而不是 JNI API —— 命令行的参数是稳定的公共接口，
 * 出问题能直接复制到终端里复现，JNI 绑定做不到这一点。
 *
 * <p>JavaCPP 首次调用时会把二进制从 jar 里解压到缓存目录。默认位置是
 * {@code ~/.javacpp/cache}，本类把它改到应用自己的配置目录下，
 * 免得一个号称"绿色包"的程序偷偷在用户家目录里留东西。
 */
@Component
public class FfmpegLocator {

    private static final Logger log = LoggerFactory.getLogger(FfmpegLocator.class);

    /** JavaCPP 读的是系统属性，不是环境变量 */
    private static final String CACHE_DIR_PROPERTY = "org.bytedeco.javacpp.cachedir";

    private final Path overrideDir;
    private final Path cacheDir;

    private volatile Path ffmpeg;
    private volatile Path ffprobe;

    // 类里有两个构造器，必须显式标注给 Spring 用的是哪个，
    // 否则容器会去找无参构造器然后启动失败
    @Autowired
    public FfmpegLocator(AppPaths appPaths,
                         @Value("${shiguang.ffmpeg.dir:}") String overrideDir) {
        this(appPaths.configDir().resolve("native-cache"),
                (overrideDir == null || overrideDir.isBlank()) ? null : Path.of(overrideDir));
    }

    /** 直接给定目录的构造入口，供测试使用。 */
    FfmpegLocator(Path cacheDir, Path overrideDir) {
        this.cacheDir = cacheDir;
        this.overrideDir = overrideDir;
    }

    /** ffmpeg 可执行文件的绝对路径。首次调用可能触发解压，之后走缓存。 */
    public Path ffmpeg() {
        Path cached = ffmpeg;
        if (cached == null) {
            synchronized (this) {
                if (ffmpeg == null) {
                    ffmpeg = resolve("ffmpeg");
                }
                cached = ffmpeg;
            }
        }
        return cached;
    }

    /** ffprobe 可执行文件的绝对路径。 */
    public Path ffprobe() {
        Path cached = ffprobe;
        if (cached == null) {
            synchronized (this) {
                if (ffprobe == null) {
                    ffprobe = resolve("ffprobe");
                }
                cached = ffprobe;
            }
        }
        return cached;
    }

    private Path resolve(String name) {
        // 显式指定的优先。留这个口子是为了让用户能换成系统装的 ffmpeg
        // （比如想要带 libx264 的 GPL 构建），或者在受限环境里手工放二进制。
        if (overrideDir != null) {
            Path candidate = overrideDir.resolve(exeName(name));
            if (!Files.isExecutable(candidate)) {
                throw new IllegalStateException(
                        "shiguang.ffmpeg.dir 指向 " + overrideDir + "，但其中没有可执行的 " + exeName(name));
            }
            log.info("使用外部指定的 {}: {}", name, candidate);
            return candidate;
        }

        prepareCacheDir();

        String path = switch (name) {
            case "ffmpeg" -> org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.ffmpeg.class);
            case "ffprobe" -> org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.ffprobe.class);
            default -> throw new IllegalArgumentException(name);
        };
        // Loader.load 解压失败时返回 null 而不是抛异常（缓存目录不可写、被删掉、
        // 平台 classifier 没打进包都会这样）。不拦下来的话，下一行 Path.of(null)
        // 抛出的 NPE 完全看不出是 ffmpeg 的问题。
        if (path == null) {
            throw new IllegalStateException(
                    "无法从依赖中解出 " + name + "。检查缓存目录 " + cacheDir + " 是否可写，"
                            + "以及构建时是否带上了当前平台的 javacpp.platform classifier。");
        }
        log.info("已就绪 {}: {}", name, path);
        return Path.of(path);
    }

    /**
     * 把 JavaCPP 的解压目录挪到应用配置目录下。
     *
     * <p>只在属性没被外部设置过时才写：命令行上显式给了 {@code -Dorg.bytedeco.javacpp.cachedir}
     * 的人，意图比这里的默认值更明确。
     */
    private void prepareCacheDir() {
        if (System.getProperty(CACHE_DIR_PROPERTY) != null) {
            return;
        }
        try {
            Files.createDirectories(cacheDir);
            // 解出来的是可执行文件，权限跟其他应用数据一样收紧到仅属主可访问
            FileSecurity.hardenDirectory(cacheDir);
        } catch (IOException e) {
            log.warn("无法创建原生库缓存目录 {}，回退到 JavaCPP 默认位置", cacheDir, e);
            return;
        }
        System.setProperty(CACHE_DIR_PROPERTY, cacheDir.toAbsolutePath().toString());
    }

    private static String exeName(String name) {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }
}
