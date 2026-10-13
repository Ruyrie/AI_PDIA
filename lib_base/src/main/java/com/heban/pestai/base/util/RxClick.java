package com.heban.pestai.base.util;

import android.view.View;

import androidx.annotation.NonNull;

import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * 按钮防连点：RxJava throttleFirst，500ms 内只响应第一次点击。
 * DataBinding 中使用 {@code app:onSafeClick="@{() -> vm.onXxx()}"}（见 BaseBindingAdapters）。
 */
public final class RxClick {

    public static final long WINDOW_MS = 500;

    private RxClick() {
    }

    public static void throttle(@NonNull View view, @NonNull View.OnClickListener listener) {
        PublishSubject<View> clicks = PublishSubject.create();
        //noinspection ResultOfMethodCallIgnored 订阅与 View 同生命周期，随 View 一起回收
        clicks.throttleFirst(WINDOW_MS, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
                .subscribe(listener::onClick);
        view.setOnClickListener(clicks::onNext);
    }
}
