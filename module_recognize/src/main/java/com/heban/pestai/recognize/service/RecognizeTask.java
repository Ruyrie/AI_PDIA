package com.heban.pestai.recognize.service;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

/**
 * 页面操作识别任务的接口，由 {@link RecognizeService.RecognizeBinder} 实现；
 * AnalyzingViewModel 只依赖该接口，单元测试中替换为假实现。所有方法都在主线程调用。
 */
public interface RecognizeTask {

    /** 注册进度监听，并立即用当前快照回调一次（回放） */
    @MainThread
    void attach(@NonNull ProgressListener listener);

    /** 清除监听，页面不可见时不再回调 */
    @MainThread
    void detach();

    /** 取消识别：dispose 整条 RxJava 链（OkHttp 取消请求），快照改为 CANCELED 并结束服务 */
    @MainThread
    void cancel();

    /** 页面已处理终态（成功 / 失败）：结束服务，页面解绑后销毁 */
    @MainThread
    void finishTask();
}
