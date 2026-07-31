package com.shiguang.nas.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 文件系统权限加固。
 *
 * <p>这是一款私有媒体库：数据库、密钥、原始照片全部只应对当前用户可读。
 * 默认的 umask / Windows 继承 ACL 都会让同机其他账户能读到，必须显式收紧。
 *
 * <p>所有方法都是尽力而为：加固失败只告警不中断启动（例如 FAT32 外置硬盘不支持 ACL），
 * 但会在日志里明确留痕，方便排查"为什么这台机器上数据是敞开的"。
 */
public final class FileSecurity {

    private static final Logger log = LoggerFactory.getLogger(FileSecurity.class);

    /** POSIX 目录：rwx------ */
    private static final Set<java.nio.file.attribute.PosixFilePermission> DIR_700 =
            PosixFilePermissions.fromString("rwx------");
    /** POSIX 文件：rw------- */
    private static final Set<java.nio.file.attribute.PosixFilePermission> FILE_600 =
            PosixFilePermissions.fromString("rw-------");

    private FileSecurity() {
    }

    /** 创建目录（含父目录）并收紧到"仅当前用户可访问"。 */
    public static Path createSecureDirectory(Path dir) throws IOException {
        rejectSymlink(dir);
        Files.createDirectories(dir);
        harden(dir, true);
        return dir;
    }

    /** 收紧一个已存在的文件到"仅当前用户可读写"。 */
    public static void hardenFile(Path file) {
        harden(file, false);
    }

    /** 收紧一个已存在的目录。 */
    public static void hardenDirectory(Path dir) {
        harden(dir, true);
    }

    private static void harden(Path path, boolean directory) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (Platform.isWindows()) {
                hardenWindowsAcl(path, directory);
            } else {
                Files.setPosixFilePermissions(path, directory ? DIR_700 : FILE_600);
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            log.warn("无法收紧文件权限，该路径可能对本机其他用户可见: {} ({}: {})",
                    path, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Windows：用一条只授予属主的 ACL 覆盖掉从父目录继承下来的条目。
     *
     * <p>副作用是 SYSTEM / Administrators 也会失去访问权。对一个存放私人照片的目录，
     * 这正是我们想要的；代价是某些备份软件需要提权才能读，会在 README 里说明。
     */
    private static void hardenWindowsAcl(Path path, boolean directory) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (view == null) {
            // FAT32 / exFAT 外置盘没有 ACL 支持
            log.warn("文件系统不支持 ACL，跳过权限加固: {}", path);
            return;
        }
        UserPrincipal owner = Files.getOwner(path);
        AclEntry.Builder builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            // 让子目录/子文件继承这条独占 ACL，否则新建的照片文件又会敞开
            builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        }
        view.setAcl(List.of(builder.build()));
    }

    /**
     * 拒绝把符号链接当作数据目录使用。
     *
     * <p>防的是这种情况：攻击者（或误操作）把存储根目录指向一个符号链接，
     * 之后我们写入的所有文件都落到链接指向的位置，权限加固全部作用在错误的对象上。
     */
    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("路径是符号链接，出于安全考虑拒绝使用: " + path);
        }
    }

    /**
     * 校验 child 确实位于 parent 之下（解析完 {@code ..} 和符号链接之后）。
     * 用于所有"用户可控路径"的场景，防目录穿越。
     *
     * <p><b>两边都必须解析到真实路径再比。</b>只对 parent 调 toRealPath 而拿
     * child 的原始路径去比，在任何一级祖先是符号链接时都会误判越界——
     * macOS 的 {@code /tmp} 指向 {@code /private/tmp} 就是最常见的例子，
     * 把数据目录放在软链过的盘上也一样。
     *
     * <p>child 通常还不存在（正要创建它），所以先找到最深的那个已存在的祖先，
     * 把它解析成真实路径，再把剩下的相对部分接回去。
     */
    public static Path requireInside(Path parent, Path child) throws IOException {
        Path realParent = parent.toRealPath();
        Path normalized = child.toAbsolutePath().normalize();

        Path existing = normalized;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            Path resolved = existing.toRealPath().resolve(existing.relativize(normalized)).normalize();
            normalized = resolved;
        }

        if (!normalized.startsWith(realParent)) {
            throw new IOException("路径越界: " + child);
        }
        return normalized;
    }
}
