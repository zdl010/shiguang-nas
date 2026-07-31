package com.shiguang.nas.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 从 JPEG 里读拍摄时间。
 *
 * <p><b>为什么必须自己读</b>：ffprobe 对 JPEG 一个 tag 都不返回——它把 JPEG 当成
 * 单帧 mjpeg 视频流，根本不解析 APP1 里的 EXIF 段。结果是所有手机照片的
 * {@code taken_at} 全为空，feed 只能退回上传时间，于是"三年前的照片"和
 * "刚拍的照片"混在同一天里。这跟按时间浏览这条主线是直接冲突的。
 *
 * <p>只解析到拿到时间为止，不做通用 EXIF 库：APP1 → TIFF 头 → IFD0 →
 * Exif 子 IFD → DateTimeOriginal。越少的解析面，越少的崩溃可能。
 *
 * <p>所有异常都吞掉返回空：元数据读不到只是排序差一点，绝不能让一张畸形照片
 * 把整个上传流程带崩。
 */
public final class ExifReader {

    private static final Logger log = LoggerFactory.getLogger(ExifReader.class);

    /** EXIF 的时间格式是 {@code 2026:07:29 16:55:08}，冒号分隔日期，不是 ISO */
    private static final DateTimeFormatter EXIF_TIME =
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    /** 只读文件开头这么多字节。EXIF 一定在最前面，没必要把整张照片读进内存。 */
    private static final int HEAD_BYTES = 512 * 1024;

    private static final int TAG_DATETIME = 0x0132;          // IFD0，通常是修改时间
    private static final int TAG_EXIF_IFD_POINTER = 0x8769;
    private static final int TAG_DATETIME_ORIGINAL = 0x9003;  // 快门按下的那一刻
    private static final int TAG_DATETIME_DIGITIZED = 0x9004;

    private ExifReader() {
    }

    /**
     * 返回拍摄时间的毫秒时间戳。
     *
     * <p>EXIF 里的时间不带时区（这是格式本身的缺陷），按本机时区解释——
     * 对"自己的照片放在自己家里的机器上"这个场景，这是最接近真相的猜法。
     */
    public static Optional<Long> readTakenAt(Path file) {
        try {
            byte[] head = readHead(file);
            ByteBuffer exif = locateExifSegment(head);
            if (exif == null) {
                return Optional.empty();
            }
            return parseTiff(exif);
        } catch (Exception e) {
            // 包括 IOException、越界、格式错乱——一律当作"没有这个信息"
            log.debug("读取 EXIF 失败 {}: {}", file.getFileName(), e.toString());
            return Optional.empty();
        }
    }

    private static byte[] readHead(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(HEAD_BYTES);
        }
    }

    /**
     * 在 JPEG 的段结构里找到 APP1/Exif，返回定位到 TIFF 头的缓冲区。
     *
     * <p>JPEG 是一串 {@code 0xFF <marker> <2字节长度> <数据>}。必须老老实实按长度
     * 跳段——直接在文件里搜 "Exif" 字样会命中图像数据里的巧合字节。
     */
    private static ByteBuffer locateExifSegment(byte[] data) {
        if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return null;   // 不是 JPEG（没有 SOI）
        }
        int pos = 2;
        while (pos + 4 <= data.length) {
            if ((data[pos] & 0xFF) != 0xFF) {
                return null;                       // 段结构错乱，不猜
            }
            int marker = data[pos + 1] & 0xFF;
            // SOS(DA) 之后就是压缩图像数据了，EXIF 不会在那后面
            if (marker == 0xDA || marker == 0xD9) {
                return null;
            }
            int length = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            if (length < 2 || pos + 2 + length > data.length) {
                return null;
            }
            if (marker == 0xE1 && length >= 8
                    && data[pos + 4] == 'E' && data[pos + 5] == 'x'
                    && data[pos + 6] == 'i' && data[pos + 7] == 'f'
                    && data[pos + 8] == 0) {
                // 跳过 "Exif\0\0" 这 6 个字节，剩下的就是一个完整的 TIFF 结构
                int tiffStart = pos + 10;
                int tiffLength = length - 8;
                if (tiffLength <= 0 || tiffStart + tiffLength > data.length) {
                    return null;
                }
                return ByteBuffer.wrap(data, tiffStart, tiffLength).slice();
            }
            pos += 2 + length;
        }
        return null;
    }

    /** 解析 TIFF 头并按优先级找时间标签。 */
    private static Optional<Long> parseTiff(ByteBuffer tiff) {
        if (tiff.remaining() < 8) {
            return Optional.empty();
        }
        int b0 = tiff.get(0) & 0xFF;
        int b1 = tiff.get(1) & 0xFF;
        if (b0 == 'I' && b1 == 'I') {
            tiff.order(ByteOrder.LITTLE_ENDIAN);
        } else if (b0 == 'M' && b1 == 'M') {
            tiff.order(ByteOrder.BIG_ENDIAN);
        } else {
            return Optional.empty();
        }
        if ((tiff.getShort(2) & 0xFFFF) != 42) {     // TIFF 的魔数
            return Optional.empty();
        }
        int ifd0 = tiff.getInt(4);

        // DateTimeOriginal 在 Exif 子 IFD 里，是最可信的一个
        int exifIfd = intTag(tiff, ifd0, TAG_EXIF_IFD_POINTER);
        if (exifIfd > 0) {
            for (int tag : new int[]{TAG_DATETIME_ORIGINAL, TAG_DATETIME_DIGITIZED}) {
                Optional<Long> value = timeTag(tiff, exifIfd, tag);
                if (value.isPresent()) {
                    return value;
                }
            }
        }
        // 退而求其次：IFD0 的 DateTime。有些相机只写这个。
        return timeTag(tiff, ifd0, TAG_DATETIME);
    }

    private static Optional<Long> timeTag(ByteBuffer tiff, int ifdOffset, int wanted) {
        String raw = stringTag(tiff, ifdOffset, wanted);
        if (raw == null || raw.length() < 19) {
            return Optional.empty();
        }
        try {
            LocalDateTime when = LocalDateTime.parse(raw.substring(0, 19), EXIF_TIME);
            return Optional.of(when.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        } catch (Exception e) {
            // "0000:00:00 00:00:00" 是相机没设时间时的常见写法，解析失败很正常
            return Optional.empty();
        }
    }

    /** 遍历一个 IFD，找到指定 tag 的 ASCII 值。 */
    private static String stringTag(ByteBuffer tiff, int ifdOffset, int wanted) {
        for (int[] entry : entries(tiff, ifdOffset)) {
            if (entry[0] != wanted || entry[1] != 2) {     // type 2 = ASCII
                continue;
            }
            int count = entry[2];
            int valueOffset = entry[3];
            // 4 字节以内的值直接内联在条目里，否则这里存的是偏移量
            int start = count <= 4 ? entry[4] : valueOffset;
            if (start < 0 || start + count > tiff.limit()) {
                return null;
            }
            byte[] bytes = new byte[count];
            for (int i = 0; i < count; i++) {
                bytes[i] = tiff.get(start + i);
            }
            String text = new String(bytes, StandardCharsets.US_ASCII);
            int nul = text.indexOf('\0');
            return nul >= 0 ? text.substring(0, nul) : text;
        }
        return null;
    }

    /** 找一个 LONG/SHORT 型的 tag，用来读子 IFD 的偏移量。 */
    private static int intTag(ByteBuffer tiff, int ifdOffset, int wanted) {
        for (int[] entry : entries(tiff, ifdOffset)) {
            if (entry[0] == wanted) {
                return entry[3];
            }
        }
        return -1;
    }

    /**
     * 列出一个 IFD 的所有条目。
     *
     * <p>每条 12 字节：tag(2) + type(2) + count(4) + value/offset(4)。
     * 返回 {tag, type, count, valueAsInt, inlineOffset}。
     */
    private static java.util.List<int[]> entries(ByteBuffer tiff, int ifdOffset) {
        var result = new java.util.ArrayList<int[]>();
        if (ifdOffset < 0 || ifdOffset + 2 > tiff.limit()) {
            return result;
        }
        int count = tiff.getShort(ifdOffset) & 0xFFFF;
        // 条目数异常多说明偏移量指错了地方，别顺着错误的指针读下去
        if (count > 512 || ifdOffset + 2 + count * 12 > tiff.limit()) {
            return result;
        }
        for (int i = 0; i < count; i++) {
            int at = ifdOffset + 2 + i * 12;
            int tag = tiff.getShort(at) & 0xFFFF;
            int type = tiff.getShort(at + 2) & 0xFFFF;
            int n = tiff.getInt(at + 4);
            int value = type == 3 ? (tiff.getShort(at + 8) & 0xFFFF) : tiff.getInt(at + 8);
            result.add(new int[]{tag, type, n, value, at + 8});
        }
        return result;
    }
}
