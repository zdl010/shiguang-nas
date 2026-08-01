package com.shiguang.nas.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 执行 ffmpeg / ffprobe 的统一入口。
 *
 * <p>集中在一处是为了保证两条纪律在每次调用上都成立：
 * <ul>
 *   <li><b>必须有超时。</b>一个损坏的媒体文件能让 ffmpeg 卡住不退出，
 *       没有超时就是一条把服务器拖死的路径——而文件是用户上传的。
 *   <li><b>必须读干输出流。</b>子进程的 stdout/stderr 缓冲区满了就会阻塞，
 *       表现为"命令莫名其妙卡住"，而且只在处理大文件时才复现。
 * </ul>
 *
 * <p>参数一律用数组传，不拼 shell 命令行，从根上避免命令注入——
 * 文件名是用户可控的。
 */
@Component
public class FfmpegRunner {

    private static final Logger log = LoggerFactory.getLogger(FfmpegRunner.class);

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TRANSCODE_TIMEOUT = Duration.ofMinutes(3);

    /** 单次调用最多收多少输出，防止畸形文件让 ffmpeg 刷出几百 MB 的错误日志 */
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

    private final FfmpegLocator locator;

    public FfmpegRunner(FfmpegLocator locator) {
        this.locator = locator;
    }

    public record Result(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    public Result ffprobe(String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(locator.ffprobe().toString());
        command.addAll(List.of(args));
        return run(command, PROBE_TIMEOUT);
    }

    public Result ffmpeg(String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(locator.ffmpeg().toString());
        // -nostdin：不加的话 ffmpeg 会尝试读标准输入，在没有终端的服务进程里会挂住
        command.add("-nostdin");
        command.add("-hide_banner");
        command.addAll(List.of(args));
        return run(command, TRANSCODE_TIMEOUT);
    }

    private Result run(List<String> command, Duration timeout) throws IOException {
        Process process = locator.newProcess(command).redirectErrorStream(true).start();
        // 子进程不需要输入，立刻关掉，避免它在等 stdin
        process.getOutputStream().close();

        byte[] buffer = new byte[8192];
        var collected = new java.io.ByteArrayOutputStream();
        try (var in = process.getInputStream()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (collected.size() < MAX_OUTPUT_BYTES) {
                    collected.write(buffer, 0, read);
                }
                // 超限之后继续读但丢弃：必须把流读空，否则子进程会阻塞在写上
            }
        }

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("命令被中断", e);
        }
        if (!finished) {
            process.destroyForcibly();
            log.warn("命令超时被强制结束: {}", command.get(0));
            throw new IOException("处理超时");
        }
        return new Result(process.exitValue(), collected.toString(StandardCharsets.UTF_8));
    }

}
