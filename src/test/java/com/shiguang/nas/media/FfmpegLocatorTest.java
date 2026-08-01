package com.shiguang.nas.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 这组用例真的去执行 ffmpeg / ffprobe。
 *
 * <p>不 mock 是有意的：换成 org.bytedeco:ffmpeg 之后，唯一值得验证的事情就是
 * "这个依赖在当前平台上能不能解出一个真的能跑的二进制"。mock 掉进程调用，
 * 剩下的只是在测自己写的三行 if。
 */
class FfmpegLocatorTest {

    /**
     * 解压缓存目录必须是**整个测试类共享的静态目录**。
     *
     * <p>JavaCPP 的缓存位置是通过系统属性设置的全局状态，只在第一次解压时生效。
     * 如果每个用例都用自己的 @TempDir，第一个用例跑完目录就被删了，
     * 后面的用例仍指向那个已消失的路径，Loader.load 会返回 null。
     * 生产环境里这个属性在启动时设一次，天然没有这个问题。
     */
    @TempDir
    static Path sharedCache;

    @Test
    void 解出可执行的ffmpeg并能报出版本() throws Exception {
        FfmpegLocator locator = new FfmpegLocator(sharedCache, null);

        Path ffmpeg = locator.ffmpeg();
        assertThat(ffmpeg).exists().isExecutable();

        String output = run(ffmpeg.toString(), "-hide_banner", "-version");
        assertThat(output).startsWith("ffmpeg version");
    }

    @Test
    void 解出可执行的ffprobe() throws Exception {
        FfmpegLocator locator = new FfmpegLocator(sharedCache, null);

        Path ffprobe = locator.ffprobe();
        assertThat(ffprobe).exists().isExecutable();
        assertThat(run(ffprobe.toString(), "-hide_banner", "-version"))
                .startsWith("ffprobe version");
    }

    /**
     * HEVC 解码是选型时的决定性因素：iPhone 默认拍 HEIC 照片和 HEVC 视频，
     * 而纯 Java 方案（JCodec）解不了。这条断言掉了就说明换了个不带 HEVC 的构建。
     */
    @Test
    void 具备HEVC与H264解码能力() throws Exception {
        String decoders = run(new FfmpegLocator(sharedCache, null).ffmpeg().toString(),
                "-hide_banner", "-decoders");

        assertThat(decoders).contains(" hevc ").contains(" h264 ");
    }

    @Test
    void 能把视频抽出一帧封面图(@TempDir Path dir) throws Exception {
        Path ffmpeg = new FfmpegLocator(sharedCache, null).ffmpeg();
        Path video = dir.resolve("clip.mp4");
        Path cover = dir.resolve("cover.jpg");

        // 用 lavfi 造测试片源，避免往仓库里塞二进制素材
        run(ffmpeg.toString(), "-hide_banner", "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=15:duration=2",
                video.toString());
        run(ffmpeg.toString(), "-hide_banner", "-v", "error", "-y",
                "-ss", "1", "-i", video.toString(), "-frames:v", "1",
                "-vf", "scale=160:-1", cover.toString());

        assertThat(cover).exists();
        assertThat(Files.size(cover)).isPositive();
        // JPEG 的魔数，确认输出的确实是图片而不是一个空壳文件
        byte[] head = Files.readAllBytes(cover);
        assertThat(head[0] & 0xFF).isEqualTo(0xFF);
        assertThat(head[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void 指定的外部目录优先(@TempDir Path dir) throws Exception {
        Path external = Files.createDirectory(dir.resolve("external"));
        Path fake = external.resolve(isWindows() ? "ffmpeg.exe" : "ffmpeg");
        Files.writeString(fake, "#!/bin/sh\necho fake\n");
        fake.toFile().setExecutable(true);

        assertThat(new FfmpegLocator(sharedCache, external).ffmpeg())
                .isEqualTo(fake);
    }

    @Test
    void 外部目录里没有二进制时报错而不是静默回退(@TempDir Path dir) throws IOException {
        Path empty = Files.createDirectory(dir.resolve("empty"));

        assertThatThrownBy(() -> new FfmpegLocator(sharedCache, empty).ffmpeg())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shiguang.ffmpeg.dir");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * 起子进程一律经由 {@link FfmpegLocator#newProcess}，不要自己 new ProcessBuilder。
     *
     * <p>这条测试自己拼过一次 ProcessBuilder，于是绕开了 Locator 给 Linux 补的
     * LD_LIBRARY_PATH——本机（macOS）照跑不误，一上 Linux CI 就报
     * libva.so.2 找不到。测试和生产必须走同一条路，否则测试只能证明
     * "在我的机器上没问题"。
     */
    private static String run(String... command) throws Exception {
        Process process = new FfmpegLocator(sharedCache, null)
                .newProcess(List.of(command))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        // 首次调用要从 jar 里解压 ~90MB 的原生库，给足超时
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("命令超时: " + List.of(command));
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "命令失败(" + process.exitValue() + "): " + List.of(command) + "\n" + output);
        }
        return output;
    }
}
