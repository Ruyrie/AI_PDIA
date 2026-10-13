package com.heban.pestai.mine.util;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * 目录大小计算（清理图片缓存前统计 Glide 磁盘缓存）。纯 Java，耗时操作，必须在 io 线程调用。
 */
public final class FileSizes {

    private FileSizes() {
    }

    /**
     * 文件返回自身长度，目录递归累加所有文件长度；不存在或为空返回 0。
     * listFiles() 在目录不可读时返回 null，跳过即可。
     */
    public static long sizeOf(@Nullable File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return file.length();
        }
        File[] children = file.listFiles();
        if (children == null) {
            return 0;
        }
        long total = 0;
        for (File child : children) {
            total += sizeOf(child);
        }
        return total;
    }
}
