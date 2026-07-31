package com.shiguang.nas.common;

import java.util.Locale;

/** 操作系统判定。全项目只在这里读 os.name。 */
public final class Platform {

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private Platform() {
    }

    public static boolean isWindows() {
        return OS.contains("win");
    }

    public static boolean isMac() {
        return OS.contains("mac") || OS.contains("darwin");
    }

    public static boolean isLinux() {
        return !isWindows() && !isMac();
    }
}
