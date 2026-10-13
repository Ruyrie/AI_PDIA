package com.heban.pestai.server.client;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次流式生成的取消标记：客户端断开、SseEmitter 超时时调用 cancel()，
 * 立即执行登记的取消动作（关闭到上游模型的 OkHttp 连接）。
 */
public class StreamControl {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Runnable cancelAction;

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            Runnable action = cancelAction;
            if (action != null) {
                runQuietly(action);
            }
        }
    }

    /** 登记取消动作；如果已经取消，立即执行 */
    public void onCancel(Runnable action) {
        this.cancelAction = action;
        if (cancelled.get()) {
            runQuietly(action);
        }
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 取消动作失败不影响流程
        }
    }
}
