package com.heban.pestai.ai;

import org.junit.rules.ExternalResource;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** 把 io / computation / 主线程调度器替换为 trampoline，使 ViewModel 中的 Rx 链同步执行 */
public class RxSchedulersRule extends ExternalResource {

    @Override
    protected void before() {
        RxJavaPlugins.setIoSchedulerHandler(s -> Schedulers.trampoline());
        RxJavaPlugins.setComputationSchedulerHandler(s -> Schedulers.trampoline());
        RxJavaPlugins.setNewThreadSchedulerHandler(s -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(callable -> Schedulers.trampoline());
        RxAndroidPlugins.setMainThreadSchedulerHandler(s -> Schedulers.trampoline());
    }

    @Override
    protected void after() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }
}
