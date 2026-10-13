package com.heban.pestai.mine.testutil;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/**
 * io 与主线程调度器替换为 trampoline（同步执行）；computation 调度器替换为 {@link TestScheduler}，
 * 由测试手动推进时间（如退出登录的 5 秒超时）。
 */
public class RxTestRule extends TestWatcher {

    public final TestScheduler computation = new TestScheduler();

    @Override
    protected void starting(Description description) {
        RxJavaPlugins.setIoSchedulerHandler(s -> Schedulers.trampoline());
        RxJavaPlugins.setComputationSchedulerHandler(s -> computation);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(s -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(s -> Schedulers.trampoline());
    }

    @Override
    protected void finished(Description description) {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }
}
