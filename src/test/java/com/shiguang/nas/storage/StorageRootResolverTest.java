package com.shiguang.nas.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Windows 选盘逻辑（需求第 7 条）。
 *
 * <p>没有 Windows 机器可跑，所以把"能脱离平台判定的部分"抽出来单独验：
 * 盘符解析、可移动文件系统识别、以及"剩余空间最大者胜出"这条规则本身。
 * 真正依赖平台的只剩 {@code File.listRoots()} 和那次 PowerShell 调用。
 */
class StorageRootResolverTest {

    // ── 选盘规则 ────────────────────────────────────────────────────────

    @Test
    void 选剩余空间最大的盘() {
        Path chosen = StorageRootResolver.selectBest(List.of(
                candidate("C:/ShiguangNAS", 50L << 30),
                candidate("D:/ShiguangNAS", 900L << 30),
                candidate("E:/ShiguangNAS", 120L << 30)));

        assertThat(chosen).isEqualTo(Path.of("D:/ShiguangNAS"));
    }

    /**
     * 比的是<b>剩余</b>空间而不是总容量。
     * 一块 4TB 但已经塞满的盘，对存照片来说毫无用处。
     */
    @Test
    void 按剩余空间而不是总容量() {
        Path chosen = StorageRootResolver.selectBest(List.of(
                candidateWithTotal("C:/ShiguangNAS", 20L << 30, 4000L << 30),
                candidateWithTotal("D:/ShiguangNAS", 300L << 30, 500L << 30)));

        assertThat(chosen).isEqualTo(Path.of("D:/ShiguangNAS"));
    }

    @Test
    void 没有候选时返回null由调用方回落() {
        assertThat(StorageRootResolver.selectBest(List.of())).isNull();
    }

    // ── 盘符解析 ────────────────────────────────────────────────────────

    @Test
    void 解析PowerShell输出的固定盘列表() {
        String output = """
                C:
                D:
                """;
        assertThat(StorageRootResolver.parseDriveList(output)).containsExactly("C:", "D:");
    }

    @Test
    void 忽略输出里的表头空行和报错() {
        String output = """

                DeviceID
                --------
                C:
                Get-CimInstance : 拒绝访问。
                  E:
                """;
        // 只认 "X:" 这个形状，其余一律丢掉——把报错文本当成盘符会导致所有盘被排除
        assertThat(StorageRootResolver.parseDriveList(output)).containsExactly("C:", "E:");
    }

    @Test
    void 盘符统一成大写以便比对() {
        assertThat(StorageRootResolver.parseDriveList("c:\nd:")).containsExactly("C:", "D:");
        assertThat(StorageRootResolver.driveLetter("c:\\")).isEqualTo("C:");
        assertThat(StorageRootResolver.driveLetter("D:\\")).isEqualTo("D:");
    }

    @Test
    void 空输出解析为空集合() {
        assertThat(StorageRootResolver.parseDriveList("")).isEmpty();
        assertThat(StorageRootResolver.parseDriveList(null)).isEmpty();
        assertThat(StorageRootResolver.parseDriveList("   \n \n")).isEmpty();
    }

    // ── 可移动盘启发式（PowerShell 不可用时的兜底）──────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"exFAT", "FAT32", "FAT", "CDFS", "UDF", "EXFAT", "fat32"})
    void 识别出疑似可移动的文件系统(String fsType) {
        assertThat(StorageRootResolver.isLikelyRemovable(fsType)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NTFS", "ReFS", "ntfs"})
    void 固定盘常见文件系统不被误判(String fsType) {
        assertThat(StorageRootResolver.isLikelyRemovable(fsType)).isFalse();
    }

    @Test
    void 文件系统类型为空时不做排除() {
        // 宁可多留一个候选，也不要因为读不到类型就把唯一的盘排除掉
        assertThat(StorageRootResolver.isLikelyRemovable(null)).isFalse();
    }

    private static StorageRootResolver.Candidate candidate(String path, long usable) {
        return candidateWithTotal(path, usable, usable * 2);
    }

    private static StorageRootResolver.Candidate candidateWithTotal(String path, long usable, long total) {
        return new StorageRootResolver.Candidate(Path.of(path), usable, total, "NTFS");
    }
}
