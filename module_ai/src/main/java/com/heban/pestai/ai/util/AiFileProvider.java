package com.heban.pestai.ai.util;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;

/**
 * Q01 / Q02 输入栏「拍照」使用的 FileProvider：系统相机把照片写入本组件缓存目录 ai_camera/ 下的文件。
 * 单独子类化（而不是直接声明 androidx.core.content.FileProvider），避免与其他组件声明的 FileProvider 在合并清单中冲突；
 * authority 为 {@code ${applicationId}.ai.fileprovider}（见本组件 AndroidManifest.xml 与 res/xml/ai_file_paths.xml）。
 */
public class AiFileProvider extends FileProvider {

    public static final String AUTHORITY_SUFFIX = ".ai.fileprovider";
    /** 与 res/xml/ai_file_paths.xml 的 cache-path 一致 */
    public static final String CAMERA_DIR = "ai_camera";
    /** 拍照文件保留 1 天，之后创建新文件时清理 */
    private static final long KEEP_MS = 24L * 60 * 60 * 1000;

    /** 新建一个空的拍照文件并返回其 content:// Uri（顺便清理 1 天前的旧照片） */
    @NonNull
    public static Uri createCameraUri(@NonNull Context context) throws IOException {
        File dir = new File(context.getCacheDir(), CAMERA_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录：" + dir);
        }
        cleanOld(dir);
        File file = new File(dir, "IMG_" + System.currentTimeMillis() + ".jpg");
        if (!file.exists() && !file.createNewFile()) {
            throw new IOException("无法创建文件：" + file);
        }
        return getUriForFile(context, context.getPackageName() + AUTHORITY_SUFFIX, file);
    }

    /** 取消拍照后删除空文件 */
    public static void deleteQuietly(@NonNull Context context, @NonNull Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) {
            return;
        }
        File file = new File(new File(context.getCacheDir(), CAMERA_DIR), name);
        //noinspection ResultOfMethodCallIgnored 缓存文件，删除失败由系统清理
        file.delete();
    }

    private static void cleanOld(@NonNull File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > KEEP_MS) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
