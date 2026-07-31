package com.shiguang.nas.media;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * 用 ffprobe 读媒体元数据：尺寸、时长、拍摄时间。
 *
 * <p>这些信息本来要靠 metadata-extractor（EXIF）+ JCodec（视频头）两个库，
 * ffprobe 一次调用全覆盖，还顺带支持 HEIC 和 HEVC。
 */
@Service
public class MediaProbe {

    private static final Logger log = LoggerFactory.getLogger(MediaProbe.class);

    /**
     * 拍摄时间可能出现在很多个字段里，按可信度从高到低试。
     *
     * <p>顺序有讲究：{@code com.apple.quicktime.creationdate} 带时区且是拍摄瞬间；
     * 而 {@code creation_time} 在很多设备上是**文件写入时间**，剪辑一次就会变。
     */
    private static final List<String> DATE_TAGS = List.of(
            "com.apple.quicktime.creationdate",
            "date",
            "creation_time",
            "DateTimeOriginal");

    private final FfmpegRunner runner;
    /**
     * Spring Boot 4 用的是 Jackson 3（包名 tools.jackson.*），
     * 与 Jackson 2 的差异：异常是非受检的，遍历字段的方法从 fields() 改成了 properties()。
     */
    private final JsonMapper mapper = JsonMapper.builder().build();

    public MediaProbe(FfmpegRunner runner) {
        this.runner = runner;
    }

    public record ProbeResult(Integer width, Integer height, Long durationMs,
                              Long takenAt, String codec, boolean hasVideoStream) {
        public static ProbeResult empty() {
            return new ProbeResult(null, null, null, null, null, false);
        }
    }

    public ProbeResult probe(Path file) {
        try {
            FfmpegRunner.Result result = runner.ffprobe(
                    "-v", "error",
                    "-print_format", "json",
                    "-show_format",
                    "-show_streams",
                    file.toAbsolutePath().toString());
            if (!result.ok()) {
                log.debug("ffprobe 退出码 {}：{}", result.exitCode(), result.output());
                return ProbeResult.empty();
            }
            ProbeResult probed = parse(result.output());
            // ffprobe 把 JPEG 当单帧 mjpeg，完全不解析 APP1 里的 EXIF，
            // 所以照片的拍摄时间只能自己去读。详见 ExifReader 的类注释。
            if (probed.takenAt() == null) {
                Long exifTime = ExifReader.readTakenAt(file).orElse(null);
                if (exifTime != null) {
                    return new ProbeResult(probed.width(), probed.height(), probed.durationMs(),
                            exifTime, probed.codec(), probed.hasVideoStream());
                }
            }
            return probed;
        } catch (IOException e) {
            // 探测失败不该让上传整个失败：文件已经收下了，缺元数据只影响排序和展示
            log.warn("探测媒体元数据失败 {}: {}", file.getFileName(), e.getMessage());
            return ProbeResult.empty();
        }
    }

    private ProbeResult parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode format = root.path("format");

            Integer width = null;
            Integer height = null;
            String codec = null;
            boolean hasVideo = false;

            for (JsonNode stream : root.path("streams")) {
                if ("video".equals(stream.path("codec_type").asText())) {
                    hasVideo = true;
                    if (width == null) {
                        width = intOrNull(stream, "width");
                        height = intOrNull(stream, "height");
                        codec = stream.path("codec_name").asText("");
                    }
                }
            }

            Long durationMs = null;
            if (format.hasNonNull("duration")) {
                double seconds = format.path("duration").asDouble(0);
                if (seconds > 0) {
                    durationMs = Math.round(seconds * 1000);
                }
            }

            Long takenAt = findTakenAt(root, format);

            // 旋转 90/270 度的手机视频，容器里的宽高是未旋转前的，要换过来
            int rotation = findRotation(root);
            if ((rotation == 90 || rotation == 270) && width != null && height != null) {
                int tmp = width;
                width = height;
                height = tmp;
            }

            return new ProbeResult(width, height, durationMs, takenAt, codec, hasVideo);
        } catch (JacksonException e) {
            log.warn("解析 ffprobe 输出失败: {}", e.getMessage());
            return ProbeResult.empty();
        }
    }

    private Long findTakenAt(JsonNode root, JsonNode format) {
        for (String tag : DATE_TAGS) {
            Optional<Long> value = readTag(format.path("tags"), tag);
            if (value.isPresent()) {
                return value.get();
            }
        }
        for (JsonNode stream : root.path("streams")) {
            for (String tag : DATE_TAGS) {
                Optional<Long> value = readTag(stream.path("tags"), tag);
                if (value.isPresent()) {
                    return value.get();
                }
            }
        }
        return null;
    }

    private Optional<Long> readTag(JsonNode tags, String name) {
        if (tags == null || tags.isMissingNode()) {
            return Optional.empty();
        }
        // 标签名的大小写在不同容器里不一致，逐个不区分大小写地比
        for (var entry : tags.properties()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return parseDate(entry.getValue().asText(""));
            }
        }
        return Optional.empty();
    }

    /**
     * 解析时间字符串。
     *
     * <p>EXIF 用的是 {@code 2026:07:31 14:00:00} 这种冒号分隔的日期，
     * 不是任何标准格式，必须单独处理。
     */
    private Optional<Long> parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.strip();
        try {
            return Optional.of(Instant.parse(value).toEpochMilli());
        } catch (DateTimeParseException ignored) {
            // 继续试其他格式
        }
        try {
            return Optional.of(java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli());
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        try {
            // EXIF 风格：2026:07:31 14:00:00
            var formatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
            LocalDateTime local = LocalDateTime.parse(value, formatter);
            return Optional.of(local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        } catch (DateTimeParseException ignored) {
            // 放弃
        }
        return Optional.empty();
    }

    private int findRotation(JsonNode root) {
        for (JsonNode stream : root.path("streams")) {
            if (!"video".equals(stream.path("codec_type").asText())) {
                continue;
            }
            // 新版 ffprobe 把旋转放在 side_data_list 里，老版放在 tags.rotate
            for (JsonNode side : stream.path("side_data_list")) {
                if (side.hasNonNull("rotation")) {
                    return Math.floorMod(side.path("rotation").asInt(), 360);
                }
            }
            JsonNode rotate = stream.path("tags").path("rotate");
            if (!rotate.isMissingNode()) {
                return Math.floorMod(rotate.asInt(0), 360);
            }
        }
        return 0;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asInt() : null;
    }
}
