package com.shiguang.nas.media;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 按 HTTP Range 分段下发文件。
 *
 * <p>没有 Range 支持的话，视频只能从头下载完才能播，进度条也拖不动——
 * Safari 甚至会直接拒绝播放不支持 Range 的视频源。
 *
 * <p>只实现单区间（{@code bytes=start-end}）。多区间要求 multipart/byteranges
 * 响应体，浏览器播放器从来不用，实现它只是多一份出错的可能。
 */
@Component
public class MediaStreamer {

    private static final Logger log = LoggerFactory.getLogger(MediaStreamer.class);

    private static final int BUFFER_SIZE = 64 * 1024;

    public void stream(Path file, String contentType, String downloadName,
                       HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        if (!Files.isReadable(file)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        long length = Files.size(file);

        // Range 必须在设置任何内容头之前校验完。
        // 反过来的话，先设了 Content-Type: video/mp4 再 sendError(416)，
        // 容器会转发到 /error，而 Spring 想在那里写 JSON 错误体——
        // 撞上已经预设的 video/mp4 就抛 HttpMessageNotWritableException，
        // 客户端看到的是 500 而不是 416。
        String rangeHeader = request.getHeader("Range");
        long start = 0;
        long end = length - 1;
        boolean partial = false;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            long[] range = parseRange(rangeHeader.substring(6), length);
            if (range == null) {
                response.reset();
                // 416 必须带 Content-Range 告诉对方真实长度，否则播放器会一直重试
                response.setHeader("Content-Range", "bytes */" + length);
                response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                return;
            }
            start = range[0];
            end = range[1];
            partial = true;
        }

        response.setHeader("Accept-Ranges", "bytes");
        response.setContentType(contentType);
        // 私有内容，绝不能被中间缓存留下来；但允许浏览器自己缓存，否则每次滑动都重下
        response.setHeader("Cache-Control", "private, max-age=604800");
        // 浏览器嗅探出来的类型和我们声明的不一致时，按声明的来，堵住 MIME 混淆
        response.setHeader("X-Content-Type-Options", "nosniff");

        if (downloadName != null) {
            response.setHeader("Content-Disposition", contentDisposition(downloadName));
        }

        long contentLength = end - start + 1;
        if (partial) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
        }
        response.setHeader("Content-Length", String.valueOf(contentLength));

        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            return;
        }

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
             OutputStream out = response.getOutputStream()) {
            channel.position(start);
            byte[] buffer = new byte[BUFFER_SIZE];
            java.nio.ByteBuffer wrapper = java.nio.ByteBuffer.wrap(buffer);
            long remaining = contentLength;
            while (remaining > 0) {
                wrapper.clear();
                wrapper.limit((int) Math.min(BUFFER_SIZE, remaining));
                int read = channel.read(wrapper);
                if (read <= 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        } catch (IOException e) {
            // 用户拖进度条或关标签页会中断连接，这在媒体服务里是常态而不是故障
            log.debug("流式下发中断: {}", e.getMessage());
        }
    }

    /**
     * 解析单区间。
     *
     * @return {start, end}，不合法返回 null
     */
    static long[] parseRange(String spec, long length) {
        // 多区间（含逗号）只取第一段，仍然是合法响应
        int comma = spec.indexOf(',');
        if (comma >= 0) {
            spec = spec.substring(0, comma);
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String from = spec.substring(0, dash).strip();
        String to = spec.substring(dash + 1).strip();

        try {
            long start;
            long end;
            if (from.isEmpty()) {
                // "bytes=-500" 表示最后 500 字节
                if (to.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(to);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, length - suffix);
                end = length - 1;
            } else {
                start = Long.parseLong(from);
                end = to.isEmpty() ? length - 1 : Long.parseLong(to);
            }
            if (start < 0 || start >= length || end < start) {
                return null;
            }
            return new long[]{start, Math.min(end, length - 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 构造 Content-Disposition。
     *
     * <p>文件名里可能有中文、空格和引号。同时给 filename 和 filename*：
     * 前者是 ASCII 兜底，后者按 RFC 5987 用 UTF-8 编码，中文名才不会变乱码。
     * 引号和反斜杠必须转义，否则文件名里一个引号就能截断这个头。
     */
    static String contentDisposition(String name) {
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
