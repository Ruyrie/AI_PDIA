package com.heban.pestai.account.util;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;

/**
 * A06 完善资料 / 编辑资料「拍照」换头像使用的 FileProvider：系统相机把照片写入本组件缓存目录 account_camera/ 下的文件。
 * 单独子类化（而不是直接声明 androidx.core.content.FileProvider），避免与其他组件声明的 FileProvider 在合并清单中冲突；
 * authority 为 {@code ${applicationId}.account.fileprovider}（见本组件 AndroidManifest.xml 与 res/xml/account_file_paths.xml）。
 */
public class AccountFileProvider extends FileProvider {

    public static final String AUTHORITY_SUFFIX = ".account.fileprovider";
    /** 与 res/xml/account_file_paths.xml 的 cache-path 一致 */
    public static final String CAMERA_DIR = "account_camera";
    /** 拍照文件最多保留 1 天，之后创建新文件时清理（正常情况下离开资料页时已删除） */
    private static final long KEEP_MS = 24L * 60 * 60 * 1000;

    /** 新建一个空的拍照文件并返回其 content:// Uri（顺便清理 1 天前的旧照片） */
    @NonNull
    public static Uri createCameraUri(@NonNull Context context) throws IOException {
        File dir = cameraDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录：" + dir);
        }
        cleanOld(dir, KEEP_MS);
        File file = new File(dir, "AVATAR_" + System.currentTimeMillis() + ".jpg");
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
        //noinspection ResultOfMethodCallIgnored 缓存文件，删除失败由系统清理
        new File(cameraDir(context), name).delete();
    }

    /** 离开资料页后删除全部拍照文件（头像已上传，或用户放弃了修改） */
    public static void clearAll(@NonNull Context context) {
        cleanOld(cameraDir(context), -1);
    }

    @NonNull
    private static File cameraDir(@NonNull Context context) {
        return new File(context.getCacheDir(), CAMERA_DIR);
    }

    /** 删除修改时间早于 keepMs 之前的文件；keepMs 小于 0 时全部删除 */
    private static void cleanOld(@NonNull File dir, long keepMs) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (keepMs < 0 || now - f.lastModified() > keepMs) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
