package com.shiguang.nas.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXIF 解析的回归测试。
 *
 * <p>这里手工拼 JPEG 字节而不是塞一张真照片进仓库：真照片体积大、
 * 出了问题也不好定位是哪个字节写错了。手拼能精确控制每一种畸形情况。
 */
class ExifReaderTest {

    /** 拼一个只有 SOI + APP1(Exif) + EOI 的最小 JPEG。 */
    private static byte[] jpegWithExif(ByteOrder order, String dateTimeOriginal,
                                       String ifd0DateTime) throws Exception {
        ByteBuffer tiff = ByteBuffer.allocate(512).order(order);
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? new byte[]{'I', 'I'} : new byte[]{'M', 'M'});
        tiff.putShort((short) 42);
        tiff.putInt(8);                       // IFD0 从第 8 字节开始

        // 值区放在两个 IFD 之后，先算好偏移
        int ifd0Entries = ifd0DateTime != null ? 2 : 1;
        int ifd0At = 8;
        int exifIfdAt = ifd0At + 2 + ifd0Entries * 12 + 4;
        int exifEntries = dateTimeOriginal != null ? 1 : 0;
        int valuesAt = exifIfdAt + 2 + exifEntries * 12 + 4;

        tiff.position(ifd0At);
        tiff.putShort((short) ifd0Entries);
        if (ifd0DateTime != null) {
            tiff.putShort((short) 0x0132);    // DateTime
            tiff.putShort((short) 2);         // ASCII
            tiff.putInt(ifd0DateTime.length() + 1);
            tiff.putInt(valuesAt + 32);
        }
        tiff.putShort((short) 0x8769);        // ExifIFDPointer
        tiff.putShort((short) 4);             // LONG
        tiff.putInt(1);
        tiff.putInt(exifIfdAt);
        tiff.putInt(0);                       // 没有下一个 IFD

        tiff.position(exifIfdAt);
        tiff.putShort((short) exifEntries);
        if (dateTimeOriginal != null) {
            tiff.putShort((short) 0x9003);    // DateTimeOriginal
            tiff.putShort((short) 2);
            tiff.putInt(dateTimeOriginal.length() + 1);
            tiff.putInt(valuesAt);
        }
        tiff.putInt(0);

        if (dateTimeOriginal != null) {
            tiff.position(valuesAt);
            tiff.put(dateTimeOriginal.getBytes(StandardCharsets.US_ASCII)).put((byte) 0);
        }
        if (ifd0DateTime != null) {
            tiff.position(valuesAt + 32);
            tiff.put(ifd0DateTime.getBytes(StandardCharsets.US_ASCII)).put((byte) 0);
        }

        int tiffLength = valuesAt + 64;
        byte[] tiffBytes = new byte[tiffLength];
        tiff.rewind();
        tiff.get(tiffBytes);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0xFF, (byte) 0xD8});          // SOI
        out.write(new byte[]{(byte) 0xFF, (byte) 0xE1});          // APP1
        int segLength = 2 + 6 + tiffBytes.length;
        out.write((segLength >> 8) & 0xFF);
        out.write(segLength & 0xFF);
        out.write("Exif".getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[]{0, 0});
        out.write(tiffBytes);
        out.write(new byte[]{(byte) 0xFF, (byte) 0xD9});          // EOI
        return out.toByteArray();
    }

    @Test
    void 小端序读出DateTimeOriginal(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("a.jpg");
        Files.write(f, jpegWithExif(ByteOrder.LITTLE_ENDIAN, "2026:07:29 16:55:08", null));

        long ms = ExifReader.readTakenAt(f).orElseThrow();
        assertThat(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()))
                .isEqualTo(LocalDateTime.of(2026, 7, 29, 16, 55, 8));
    }

    /** 尼康等相机写的是大端序，两种都必须支持 */
    @Test
    void 大端序同样能读(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("b.jpg");
        Files.write(f, jpegWithExif(ByteOrder.BIG_ENDIAN, "2019:12:25 08:30:00", null));

        long ms = ExifReader.readTakenAt(f).orElseThrow();
        assertThat(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()))
                .isEqualTo(LocalDateTime.of(2019, 12, 25, 8, 30, 0));
    }

    /** 没有 DateTimeOriginal 时退回 IFD0 的 DateTime */
    @Test
    void 回退到IFD0的DateTime(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("c.jpg");
        Files.write(f, jpegWithExif(ByteOrder.LITTLE_ENDIAN, null, "2020:01:02 03:04:05"));

        long ms = ExifReader.readTakenAt(f).orElseThrow();
        assertThat(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()))
                .isEqualTo(LocalDateTime.of(2020, 1, 2, 3, 4, 5));
    }

    /** 相机没设时间时会写全零，这不是一个有效时间 */
    @Test
    void 全零时间当作没有(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("d.jpg");
        Files.write(f, jpegWithExif(ByteOrder.LITTLE_ENDIAN, "0000:00:00 00:00:00", null));
        assertThat(ExifReader.readTakenAt(f)).isEmpty();
    }

    @Test
    void 没有EXIF的JPEG返回空(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("e.jpg");
        Files.write(f, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        assertThat(ExifReader.readTakenAt(f)).isEmpty();
    }

    @Test
    void 根本不是JPEG返回空(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("f.png");
        Files.write(f, "not a jpeg at all".getBytes(StandardCharsets.UTF_8));
        assertThat(ExifReader.readTakenAt(f)).isEmpty();
    }

    /** 畸形输入绝不能抛异常——上传流程不该因为一张坏照片整个失败 */
    @Test
    void 截断与乱码都不抛异常(@TempDir Path dir) throws Exception {
        byte[] good = jpegWithExif(ByteOrder.LITTLE_ENDIAN, "2026:07:29 16:55:08", null);
        for (int cut : new int[]{2, 4, 8, 12, 20, 40, good.length / 2, good.length - 1}) {
            Path f = dir.resolve("cut" + cut + ".jpg");
            Files.write(f, java.util.Arrays.copyOf(good, cut));
            assertThat(ExifReader.readTakenAt(f)).as("截断到 %d 字节", cut).isNotNull();
        }
        // 段长度字段被改成一个荒谬的值，不能顺着它读越界
        byte[] bad = good.clone();
        bad[4] = (byte) 0xFF;
        bad[5] = (byte) 0xFF;
        Path f = dir.resolve("badlen.jpg");
        Files.write(f, bad);
        assertThat(ExifReader.readTakenAt(f)).isEmpty();
    }

    @Test
    void 空文件返回空(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("empty.jpg");
        Files.write(f, new byte[0]);
        assertThat(ExifReader.readTakenAt(f)).isEmpty();
    }
}
