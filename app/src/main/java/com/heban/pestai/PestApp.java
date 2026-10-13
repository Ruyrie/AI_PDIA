package com.heban.pestai;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.heban.pestai.base.AppHost;
import com.heban.pestai.base.ReloginExempt;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.service.IUserService;
import com.heban.pestai.common.service.ServiceHub;
import com.heban.pestai.di.AppComponent;
import com.heban.pestai.di.DaggerAppComponent;
import com.heban.pestai.network.ApiException;

import java.io.IOException;
import java.lang.ref.WeakReference;

import io.reactivex.rxjava3.exceptions.UndeliverableException;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;

/**
 * 应用入口：创建 Dagger AppComponent，注册组件间服务，处理全局「需要重新登录」事件。
 */
public class PestApp extends Application implements AppHost {

    /** 登录页读取：是否因登录过期而进入（module_account 的 LoginActivity 使用同名键） */
    public static final String EXTRA_SESSION_EXPIRED = "session_expired";

    private static final String TAG = "PestApp";

    private AppComponent component;
    @Nullable
    private WeakReference<Activity> resumedActivity;

    @Override
    public void onCreate() {
        super.onCreate();
        installRxErrorHandler();
        component = DaggerAppComponent.factory().create(this);

        ServiceHub.register(IUserService.class, component.userService());
        ServiceHub.register(IRecordService.class, component.recordService());

        registerActivityLifecycleCallbacks(new ResumedActivityTracker());
        //noinspection ResultOfMethodCallIgnored 与应用同生命周期
        component.sessionEvents().reloginEvents().subscribe(ignored -> onReloginRequired());
    }

    /**
     * 订阅已取消后迟到的错误（如页面关闭时请求被取消产生的 IOException）不会再交给任何观察者，
     * RxJava 默认会作为 UndeliverableException 抛出导致崩溃。这里只记录网络 / 中断类错误，
     * 程序缺陷类异常仍交给线程的默认处理器。
     */
    private static void installRxErrorHandler() {
        RxJavaPlugins.setErrorHandler(e -> {
            Throwable error = e instanceof UndeliverableException && e.getCause() != null ? e.getCause() : e;
            if (error instanceof IOException || error instanceof InterruptedException
                    || error instanceof ApiException) {
                Log.w(TAG, "Undeliverable error ignored", error);
                return;
            }
            if (error instanceof NullPointerException || error instanceof IllegalArgumentException
                    || error instanceof IllegalStateException) {
                Thread thread = Thread.currentThread();
                Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
                if (handler != null) {
                    handler.uncaughtException(thread, error);
                }
                return;
            }
            Log.w(TAG, "Undeliverable error", error);
        });
    }

    /** 令牌失效：回到登录页并清空任务栈；闪屏、登录页等实现 ReloginExempt 的页面在前台时忽略 */
    private void onReloginRequired() {
        Activity top = resumedActivity == null ? null : resumedActivity.get();
        if (top instanceof ReloginExempt) {
            return;
        }
        Intent intent = RouterPath.intent(this, RouterPath.LOGIN)
                .putExtra(EXTRA_SESSION_EXPIRED, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    @Override
    public ViewModelProvider.Factory getViewModelFactory() {
        return component.viewModelFactory();
    }

    @Override
    public Object getComponent() {
        return component;
    }

    private final class ResumedActivityTracker implements ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            resumedActivity = new WeakReference<>(activity);
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
            if (resumedActivity != null && resumedActivity.get() == activity) {
                resumedActivity = null;
            }
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }
}
