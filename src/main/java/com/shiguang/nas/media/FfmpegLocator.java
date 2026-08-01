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


    /**
     * 开机自检：真的把 ffmpeg 跑一次。
     *
     * <p>光解压出可执行文件不代表它能运行。Linux 上 ffmpeg 链接了
     * libdrm / libva / libasound 这些系统库，jar 里不捆（而且 x86_64 和 arm64
     * 各自缺的还不一样），精简过的服务器镜像上多半没装。
     *
     * <p>不做这个自检的话，症状是：一切看起来正常，照片能传上去，
     * 但缩略图永远是"处理中"，真正的原因埋在数据库 thumb_error 字段里，
     * 普通用户没有任何线索。宁可在启动时喊一嗓子，把该装的包名直接告诉他。
     *
     * <p>失败不阻止启动：媒体库的浏览、上传、账号都还能用，只是没有缩略图。
     */
    @jakarta.annotation.PostConstruct
    void selfCheck() {
        try {
            Process p = newProcess(java.util.List.of(ffmpeg().toString(), "-hide_banner", "-version"))
                    .redirectErrorStream(true)
                    .start();
            p.getOutputStream().close();
            String output = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("ffmpeg 自检超时，缩略图可能无法生成");
                return;
            }
            if (p.exitValue() != 0) {
                // 有些失败（比如动态库缺失）会把原因写在 stderr，也有些什么都不输出，
                // 所以退出码必须带上，否则用户看到的是一行空白
                warnUnusable("退出码 " + p.exitValue()
                        + (output.isBlank() ? "，没有任何输出" : "：" + output.strip()));
                return;
            }
            log.info("ffmpeg 自检通过: {}", output.lines().findFirst().orElse("").strip());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            warnUnusable(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void warnUnusable(String detail) {
        log.warn("");
        log.warn("╔══════════════════════════════════════════════════════════════╗");
        log.warn("║  ffmpeg 跑不起来，缩略图和视频封面将无法生成                  ║");
        log.warn("╚══════════════════════════════════════════════════════════════╝");
        log.warn("  {}", detail == null ? "(无详情)" : detail.strip());
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            log.warn("");
            log.warn("  Linux 上通常是缺系统库。装上这些就好：");
            log.warn("    Debian/Ubuntu:  sudo apt install libasound2t64 libpulse0 libxcb1 \\");
            log.warn("                        libxcb-shm0 libva2 libva-drm2 libdrm2");
            log.warn("    Fedora/RHEL:    sudo dnf install alsa-lib pulseaudio-libs libxcb \\");
            log.warn("                        libva libdrm");
            log.warn("  （老版本 Ubuntu 上 libasound2t64 叫 libasound2）");
        }
        log.warn("");
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
     * 按 ffmpeg 二进制的要求配好一个 {@link ProcessBuilder}。
     *
     * <p><b>凡是要启动这些二进制的地方都必须走这里</b>，别自己 new ProcessBuilder。
     * 原因藏在 ELF 的两个字段之间：ffmpeg 可执行文件带 {@code RUNPATH=$ORIGIN/}，
     * 所以能找到紧挨着的 libavcodec、libavutil；但这两个库<b>自己没有 RUNPATH</b>，
     * 而 {@code DT_RUNPATH} 按规范<b>不传递给间接依赖</b>（这正是它与已废弃的
     * {@code DT_RPATH} 的区别）。于是链接器解析 libavcodec → libva 时手上没有
     * 任何线索，报出 {@code libva.so.2: cannot open shared object file}——
     * 而那个文件就躺在同一个目录里。
     *
     * <p>补一个 {@code LD_LIBRARY_PATH} 就够了。macOS 用
     * {@code @rpath}/{@code @loader_path} 没有这个断链，Windows 默认搜 exe 同目录，
     * 所以只有 Linux 需要，但设了对另外两个平台无害——它们不看这个变量。
     */
    public ProcessBuilder newProcess(java.util.List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        Path dir = Path.of(command.get(0)).getParent();
        if (dir != null) {
            var env = builder.environment();
            String existing = env.get("LD_LIBRARY_PATH");
            env.put("LD_LIBRARY_PATH", existing == null || existing.isBlank()
                    ? dir.toString()
                    : dir + java.io.File.pathSeparator + existing);
        }
        return builder;
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
