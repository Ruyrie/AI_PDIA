package com.heban.pestai.recognize.util;

import android.content.Context;

import androidx.annotation.NonNull;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * {@link TextSource} 的实现：使用 Application Context 读取字符串资源（不会泄漏页面）。
 */
@Singleton
public class ResourceTextSource implements TextSource {

    private final Context context;

    @Inject
    public ResourceTextSource(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @NonNull
    @Override
    public String get(int id, @NonNull Object... args) {
        return args.length == 0 ? context.getString(id) : context.getString(id, args);
    }
}
