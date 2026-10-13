package com.heban.pestai.mine.data;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 当前安装包的版本名 / 版本号（M01「v1.0.0」、M02「版本 1.0.0」、M03「版本 1.0.0（1）」）。
 * module_mine 是库组件，不能引用壳工程的 BuildConfig，因此用 PackageManager 读取本应用的 versionName / versionCode。
 */
@Singleton
public class AppVersion {

    private final String name;
    private final long code;

    @Inject
    public AppVersion(Context context) {
        String n = "--";
        long c = 0;
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = pm.getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                info = pm.getPackageInfo(context.getPackageName(), 0);
            }
            if (info.versionName != null) {
                n = info.versionName;
            }
            c = info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException ignored) {
            // 查询本应用自身不会失败
        }
        this.name = n;
        this.code = c;
    }

    /** 单元测试或预留的更新逻辑中直接指定版本 */
    public AppVersion(@NonNull String name, long code) {
        this.name = name;
        this.code = code;
    }

    /** 版本名，如 1.0.0 */
    @NonNull
    public String name() {
        return name;
    }

    /** 版本号，如 1 */
    public long code() {
        return code;
    }
}
