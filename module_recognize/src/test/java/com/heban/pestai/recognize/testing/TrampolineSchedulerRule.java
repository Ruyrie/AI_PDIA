package com.heban.pestai.recognize.testing;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * 把 RxJava 的 io / computation 与 RxAndroid 的主线程调度器都替换为 trampoline，订阅同步执行。
 * 必须在第一次访问 AndroidSchedulers 之前生效（JVM 单元测试中没有主线程 Looper）。
 */
public class TrampolineSchedulerRule extends TestWatcher {

    @Override
    protected void starting(Description description) {
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxJavaPlugins.setComputationSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxJavaPlugins.setNewThreadSchedulerHandler(scheduler -> Schedulers.trampoline());
    }

    @Override
    protected void finished(Description description) {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }
}
