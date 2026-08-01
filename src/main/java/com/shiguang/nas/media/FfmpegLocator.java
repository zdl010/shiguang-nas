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

        Path path = extractExecutable(name);
        log.info("已就绪 {}: {}", name, path);
        return path;
    }


    /**
     * 从依赖里解出 ffmpeg / ffprobe 可执行文件。
     *
     * <p><b>刻意不用 {@code Loader.load(ffmpeg.class)}</b>。那个方法看起来更正规，
     * 但 {@code org.bytedeco.ffmpeg.ffmpeg} 上标着
     * {@code @Properties(inherit = {avdevice.class, ...})}，于是它会顺着继承链
     * 把 avdevice 等一整套 <b>JNI 绑定</b>也加载起来。而我们全程用子进程调命令行
     * （见 {@link FfmpegRunner}），一个 JNI 绑定都用不上——白白多出七个可能加载失败的点。
     * 无头 Linux 上它就是这么炸的：{@code no jniavdevice in java.library.path}。
     *
     * <p>但也不能只解出那一个可执行文件：它动态链接了同目录下的
     * {@code libavdevice} / {@code libavcodec} 等，少一个就 dyld/ld 报错起不来。
     * 所以这里解压<b>整个平台目录</b>，只是不去 load 其中任何一个动态库。
     */
    private Path extractExecutable(String name) {
        String platform = org.bytedeco.javacpp.Loader.getPlatform();
        String resource = "/org/bytedeco/ffmpeg/" + platform + "/";
        try {
            java.io.File[] dirs = org.bytedeco.javacpp.Loader.cacheResources(resource);
            if (dirs == null || dirs.length == 0) {
                throw new IllegalStateException(
                        "依赖里没有 " + resource + "。构建时可能没带上当前平台（" + platform
                                + "）的 javacpp.platform classifier。");
            }
            java.io.File exe = new java.io.File(dirs[0], exeName(name));
            if (!exe.isFile()) {
                throw new IllegalStateException("解压出来的目录里没有 " + exe);
            }
            // 解压出的文件默认没有执行位。Loader.load 会代劳，这条路子得自己设。
            if (!exe.canExecute() && !exe.setExecutable(true)) {
                throw new IllegalStateException("无法给 " + exe + " 加上执行权限");
            }
            return exe.toPath();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "无法从依赖中解出 " + name + "：" + e.getMessage()
                            + "。检查缓存目录 " + cacheDir + " 是否可写。", e);
        }
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
