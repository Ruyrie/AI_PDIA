package com.heban.pestai.common.session;

import androidx.annotation.NonNull;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.Subject;

/**
 * 全局「需要重新登录」事件：AuthInterceptor 收到 401 / 40101 时清除登录态并发出，
 * 壳工程 PestApp 订阅后跳转登录页（前台页面实现 ReloginExempt 时忽略）。
 */
@Singleton
public class SessionEvents {

    private final Subject<Boolean> relogin = PublishSubject.<Boolean>create().toSerialized();

    @Inject
    public SessionEvents() {
    }

    /** 可在任意线程调用 */
    public void postRelogin() {
        relogin.onNext(Boolean.TRUE);
    }

    /** 在主线程回调；1 秒内的多次事件（并发请求同时 401）只保留第一次 */
    @NonNull
    public Observable<Boolean> reloginEvents() {
        return relogin.throttleFirst(1, TimeUnit.SECONDS).observeOn(AndroidSchedulers.mainThread());
    }
}
