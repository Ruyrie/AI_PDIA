package com.heban.pestai.account.testutil;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;

/**
 * io / newThread / single / 主线程调度器替换为 trampoline（同步执行）；
 * computation 调度器替换为 TestScheduler，用 {@link #advance(long)} 推进时间（倒计时、闪屏计时、超时、防连点）。
 */
public class RxTestRule extends TestWatcher {

    public final TestScheduler computation = new TestScheduler();

    @Override
    protected void starting(Description description) {
        RxJavaPlugins.setIoSchedulerHandler(s -> Schedulers.trampoline());
        RxJavaPlugins.setNewThreadSchedulerHandler(s -> Schedulers.trampoline());
        RxJavaPlugins.setSingleSchedulerHandler(s -> Schedulers.trampoline());
        RxJavaPlugins.setComputationSchedulerHandler(s -> computation);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(c -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(s -> Schedulers.trampoline());
    }

    @Override
    protected void finished(Description description) {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    /** 推进 computation 调度器的时间（毫秒） */
    public void advance(long millis) {
        computation.advanceTimeBy(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 防连点窗口已过：推进 1 秒 */
    public void passThrottle() {
        advance(1000);
    }
}
