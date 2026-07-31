package com.shiguang.nas.storage;

import com.shiguang.nas.common.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 存储根目录探测（需求第 7 条）。
 *
 * <p>Windows 下默认选"剩余空间最大的固定磁盘"，Linux/macOS 用各平台惯例位置。
 *
 * <p><b>探测结果只在首次启动时使用一次，随后固化进配置文件。</b>
 * 否则插拔一块移动硬盘就会让存储目录漂移，用户的照片会分散到两个地方——
 * 这在媒体库产品里是灾难性的。
 */
public final class StorageRootResolver {

    private static final Logger log = LoggerFactory.getLogger(StorageRootResolver.class);

    private static final String DIR_NAME = "ShiguangNAS";
    /** 低于这个可用空间的盘不作为候选（2 GiB） */
    private static final long MIN_USABLE_BYTES = 2L * 1024 * 1024 * 1024;

    /**
     * 明显不是固定盘的文件系统，用于 PowerShell 查询失败时的兜底过滤。
     *
     * <p>exFAT/FAT32 在现代 Windows 上几乎只出现在 U 盘和存储卡上；
     * CDFS/UDF 是光盘。这套判断不如查 DriveType 准（U 盘也可以格成 NTFS），
     * 所以只在拿不到 DriveType 时才用。
     */
    private static final java.util.Set<String> LIKELY_REMOVABLE_FS =
            java.util.Set.of("EXFAT", "FAT32", "FAT", "CDFS", "UDF");

    /** 查询盘符类型的超时。宁可退回启发式，也不能让首次启动卡在这里。 */
    private static final java.time.Duration DRIVE_TYPE_TIMEOUT = java.time.Duration.ofSeconds(6);

    private StorageRootResolver() {
    }

    /** 一个候选存储位置。 */
    public record Candidate(Path root, long usableBytes, long totalBytes, String fsType) {
    }

    /** 返回首次启动时应当使用的存储根目录（尚未创建）。 */
    public static Path resolveDefault() {
        if (Platform.isWindows()) {
            List<Candidate> candidates = listWindowsCandidates();
            Path chosen = selectBest(candidates);
            if (chosen != null) {
                log.info("Windows 候选盘 {} 个，选中剩余空间最大的固定盘: {}",
                        candidates.size(), chosen);
                return chosen;
            }
            log.warn("没有找到合适的固定磁盘，回落到用户目录");
            return userHome().resolve(DIR_NAME);
        }
        if (Platform.isMac()) {
            // 刻意放在家目录而不是 Application Support：媒体库是用户自己的资产，
            // 要在 Finder 里一眼能看到、能整个拖到移动硬盘。配置和密钥仍留在
            // Application Support，两者分开（与 Windows 上的 LOCALAPPDATA / 数据盘 一致）。
            return userHome().resolve(DIR_NAME);
        }
        // Linux：优先系统级目录，没权限就回落到家目录
        Path systemWide = Path.of("/var/lib/shiguang-nas");
        if (Files.isDirectory(systemWide) ? Files.isWritable(systemWide)
                                          : Files.isWritable(systemWide.getParent())) {
            return systemWide;
        }
        return userHome().resolve(".shiguang-nas");
    }

    /**
     * 列出 Windows 上所有可用作存储的固定磁盘，供设置页展示给用户选择。
     * 非 Windows 平台返回单元素列表（当前默认位置）。
     */
    public static List<Candidate> listCandidates() {
        if (Platform.isWindows()) {
            return listWindowsCandidates();
        }
        Path root = resolveDefault();
        return List.of(describe(root));
    }

    /** 从候选里挑剩余空间最大的。抽出来是为了能脱离 Windows 做单元测试。 */
    static Path selectBest(List<Candidate> candidates) {
        return candidates.stream()
                .max(Comparator.comparingLong(Candidate::usableBytes))
                .map(Candidate::root)
                .orElse(null);
    }

    private static List<Candidate> listWindowsCandidates() {
        // 先问系统哪些是固定盘。拿不到就返回 null，走下面的文件系统类型启发式。
        java.util.Set<String> fixedDrives = queryFixedDrives();

        List<Candidate> result = new ArrayList<>();
        for (File drive : File.listRoots()) {
            try {
                String letter = driveLetter(drive.getPath());

                if (fixedDrives != null) {
                    // DriveType=3 才是固定盘。U 盘（2）、网络盘（4）、光驱（5）全排除：
                    // 把媒体库建在 U 盘上，拔掉之后整个库就找不到了；
                    // 网络盘断连则会让每次启动都卡在 IO 上。
                    if (!fixedDrives.contains(letter)) {
                        log.debug("跳过非固定盘 {}", drive);
                        continue;
                    }
                }

                // getTotalSpace()==0 说明是光驱、读卡器空槽或未挂载的盘符
                long total = drive.getTotalSpace();
                if (total <= 0) {
                    continue;
                }
                long usable = drive.getUsableSpace();
                if (usable < MIN_USABLE_BYTES) {
                    continue;
                }
                Path drivePath = drive.toPath();
                FileStore store = Files.getFileStore(drivePath);
                if (store.isReadOnly()) {
                    continue;
                }
                String fsType = store.type();
                if (fixedDrives == null && isLikelyRemovable(fsType)) {
                    log.debug("跳过疑似可移动盘 {}（文件系统 {}）", drive, fsType);
                    continue;
                }
                result.add(new Candidate(drivePath.resolve(DIR_NAME), usable, total, fsType));
            } catch (IOException | SecurityException e) {
                // 网络盘断连、权限不足等：跳过，不让一块坏盘卡住启动
                log.debug("跳过磁盘 {}: {}", drive, e.getMessage());
            }
        }
        return result;
    }

    static boolean isLikelyRemovable(String fsType) {
        return fsType != null
                && LIKELY_REMOVABLE_FS.contains(fsType.trim().toUpperCase(java.util.Locale.ROOT));
    }

    /** "C:\\" → "C:"，用于和 Win32_LogicalDisk 的 DeviceID 对齐。 */
    static String driveLetter(String rootPath) {
        String value = rootPath == null ? "" : rootPath.trim();
        return value.length() >= 2 ? value.substring(0, 2).toUpperCase(java.util.Locale.ROOT) : value;
    }

    /**
     * 解析 PowerShell 输出的固定盘符列表。
     *
     * @return 形如 {@code {"C:", "D:"}}；输入为空则返回空集合
     */
    static java.util.Set<String> parseDriveList(String output) {
        java.util.Set<String> drives = new java.util.LinkedHashSet<>();
        if (output == null) {
            return drives;
        }
        for (String line : output.split("\\R")) {
            String value = line.trim();
            // 只认 "X:" 这种形状，其余（表头、空行、报错信息）一律忽略
            if (value.matches("(?i)[A-Z]:")) {
                drives.add(value.toUpperCase(java.util.Locale.ROOT));
            }
        }
        return drives;
    }

    /**
     * 问 Windows 哪些盘是固定磁盘（Win32_LogicalDisk.DriveType == 3）。
     *
     * <p>Java 没有查询盘符类型的 API，NIO 的 {@link FileStore#type()} 给的是
     * 文件系统类型（NTFS/exFAT），不是"可移动/固定/网络"。所以只能问系统。
     *
     * <p>用 PowerShell 的 Get-CimInstance 而不是 wmic：后者在 Windows 11 24H2
     * 之后已经不再预装。
     *
     * @return 固定盘集合；查询失败返回 null（调用方据此退回启发式判断）
     */
    private static java.util.Set<String> queryFixedDrives() {
        try {
            Process process = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-CimInstance Win32_LogicalDisk | "
                            + "Where-Object { $_.DriveType -eq 3 } | "
                            + "Select-Object -ExpandProperty DeviceID")
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();

            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(DRIVE_TYPE_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                log.debug("查询盘符类型超时，改用文件系统类型判断");
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            java.util.Set<String> drives = parseDriveList(output);
            // 一个都没解析出来说明输出不是预期格式，别把所有盘都排除掉
            return drives.isEmpty() ? null : drives;
        } catch (Exception e) {
            log.debug("查询盘符类型失败，改用文件系统类型判断: {}", e.getMessage());
            return null;
        }
    }

    private static Candidate describe(Path root) {
        try {
            Path probe = Files.exists(root) ? root : root.getParent();
            FileStore store = Files.getFileStore(probe);
            return new Candidate(root, store.getUsableSpace(), store.getTotalSpace(), store.type());
        } catch (IOException e) {
            return new Candidate(root, 0, 0, "unknown");
        }
    }

    private static Path userHome() {
        return Path.of(System.getProperty("user.home"));
    }
}
