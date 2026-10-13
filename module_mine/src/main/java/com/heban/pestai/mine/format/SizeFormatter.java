package com.heban.pestai.mine.format;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * 字节数格式化（模块09 技术准备文档 2.8 第 2 节「SizeFormatter 规则」）：
 * 小于 1 KB 显示「N B」；小于 1 MB 显示整数「N KB」；否则显示 MB，小于 10 MB 保留 1 位小数（「3.5 MB」），其余取整（「128 MB」）。
 */
public final class SizeFormatter {

    private static final long KB = 1024L;
    private static final long MB = KB * 1024;

    private SizeFormatter() {
    }

    @NonNull
    public static String format(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < KB) {
            return bytes + " B";
        }
        if (bytes < MB) {
            long kb = Math.round(bytes / (double) KB);
            if (kb < 1024) {
                return kb + " KB";
            }
            // 1023.5 KB 以上四舍五入后等于 1 MB，按 MB 显示
        }
        double mb = bytes / (double) MB;
        double oneDecimal = Math.round(mb * 10) / 10.0;
        if (oneDecimal < 10) {
            return String.format(Locale.ROOT, "%.1f MB", oneDecimal);
        }
        return Math.round(mb) + " MB";
    }
}
