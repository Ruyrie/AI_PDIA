package com.heban.pestai.server.client;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 问答的自动备用（docs/后台管理接口.md「八、模型自动备用」）：主模型为 DeepSeek（RoutingChatClient，CHAT_MODEL），
 * 备用模型为通义千问（QwenChatClient，CHAT_FALLBACK_MODEL）。App 不感知，回答所用模型通过 {@link StreamCallback#onModel} 告知调用方。
 * <ul>
 *   <li>只有主模型在<b>任何思考 / 正文片段送达回调之前</b>失败才改用备用模型：HTTP 错误、连接失败、流没有以 [DONE] 结束
 *       （都表现为 ChatClientException），或开始请求后 20 秒内没有任何片段（首片段超时：取消主模型的上游请求）。
 *       已经送出片段后出错保持原来的行为（异常交给 AiChatService，App 显示「回答中断」）；</li>
 *   <li>熔断：改用备用模型后 60 秒内的请求直接交给备用模型（{@link FallbackBreaker}），之后再试主模型；</li>
 *   <li>不改用备用模型的情况：开关 AI_FALLBACK_ENABLED 关闭、备用模型没有 Key（DASHSCOPE_API_KEY）、主模型是 Mock、
 *       客户端已断开（control 已取消）。前三种情况下直接调用主模型，没有首片段超时，行为与原来完全相同；</li>
 *   <li>备用模型失败（无论是否已送出片段）不再回到主模型，异常原样交给调用方。</li>
 * </ul>
 * 能否看图（supportsImages）以主模型为准：CHAT_IMAGE_INPUT=false 时带图提问仍由 AiChatService 改交识图模型。
 */
public class FallbackChatClient implements ChatClient {

    /** 默认首片段时限：开始请求主模型后这么久没有任何思考 / 正文片段，取消主模型改用备用模型 */
    public static final Duration FIRST_CHUNK_TIMEOUT = Duration.ofSeconds(20);

    private final ChatClient primary;
    private final ChatClient fallback;
    private final FallbackBreaker breaker;
    private final long firstChunkTimeoutMillis;
    /** 首片段超时的定时器：一个守护线程，各次问答共用 */
    private final ScheduledExecutorService timer;

    public FallbackChatClient(ChatClient primary, ChatClient fallback, FallbackBreaker breaker,
                              Duration firstChunkTimeout) {
        this.primary = primary;
        this.fallback = fallback;
        this.breaker = breaker;
        this.firstChunkTimeoutMillis = firstChunkTimeout.toMillis();
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "chat-first-chunk");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 应用停止时由 Spring 调用（@Bean 推断的销毁方法） */
    public void shutdown() {
        timer.shutdownNow();
    }

    public FallbackBreaker breaker() {
        return breaker;
    }

    @Override
    public void stream(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
            throws ChatClientException {
        if (!breaker.available(primary.isMock())) {
            callback.onModel(primary.modelName(), false, null);
            primary.stream(messages, callback, control);
            return;
        }
        String primaryName = primary.modelName();
        String failure;
        if (breaker.isOpen()) {
            failure = "主模型 " + primaryName + " 最近失败过（熔断中，剩余约 " + breaker.remainingSeconds()
                    + " 秒），本次直接使用备用模型";
        } else {
            callback.onModel(primaryName, false, null);
            String error = streamPrimary(messages, callback, control);
            if (error == null) {
                return;
            }
            breaker.recordFailure();
            if (control.isCancelled()) {
                return;
            }
            failure = "主模型 " + primaryName + " " + error;
        }
        callback.onModel(fallback.modelName(), true, failure);
        fallback.stream(messages, callback, control);
    }

    /**
     * 调用主模型。返回 null：已完成（成功、客户端断开，或已送出片段后失败时抛出异常）；
     * 返回失败原因：在任何片段之前失败，可以改用备用模型。
     */
    private String streamPrimary(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
            throws ChatClientException {
        Attempt attempt = new Attempt(callback);
        // 主模型单独一个取消标记：首片段超时只取消主模型的请求，不影响整次生成；客户端断开时一并取消
        StreamControl primaryControl = new StreamControl();
        control.onCancel(primaryControl::cancel);
        ScheduledFuture<?> timeout = scheduleTimeout(attempt, primaryControl);
        try {
            primary.stream(messages, attempt, primaryControl);
        } catch (ChatClientException e) {
            if (attempt.hasOutput() || control.isCancelled()) {
                throw e;
            }
            return attempt.isTimedOut() ? timeoutReason() : "失败：" + FallbackBreaker.summary(e.getMessage());
        } finally {
            if (timeout != null) {
                timeout.cancel(false);
            }
        }
        if (attempt.isTimedOut() && !control.isCancelled()) {
            return timeoutReason();
        }
        return null;
    }

    private ScheduledFuture<?> scheduleTimeout(Attempt attempt, StreamControl primaryControl) {
        try {
            return timer.schedule(() -> {
                if (attempt.timeOut()) {
                    primaryControl.cancel();
                }
            }, firstChunkTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // 定时器已关闭（应用正在停止）：本次不设首片段时限
            return null;
        }
    }

    private String timeoutReason() {
        return (firstChunkTimeoutMillis % 1000 == 0 ? firstChunkTimeoutMillis / 1000 + " 秒" : firstChunkTimeoutMillis + " 毫秒")
                + "内没有返回任何内容（已取消）";
    }

    @Override
    public boolean isMock() {
        return primary.isMock();
    }

    @Override
    public boolean supportsImages() {
        return primary.supportsImages();
    }

    /** 主模型的模型名（实际回答所用的模型以 onModel 回调为准） */
    @Override
    public String modelName() {
        return primary.modelName();
    }

    /**
     * 主模型的一次尝试：转发片段并记录是否已送出。状态 WAITING → STREAMING（第一个片段）或 WAITING → TIMED_OUT（首片段超时），
     * 两者互斥；超时之后迟到的片段直接丢弃，不会送到客户端。
     */
    private static final class Attempt implements StreamCallback {

        private static final int WAITING = 0;
        private static final int STREAMING = 1;
        private static final int TIMED_OUT = 2;

        private final StreamCallback target;
        private final AtomicInteger state = new AtomicInteger(WAITING);

        Attempt(StreamCallback target) {
            this.target = target;
        }

        @Override
        public void onDelta(String delta) throws IOException {
            if (admit()) {
                target.onDelta(delta);
            }
        }

        @Override
        public void onReasoning(String delta) throws IOException {
            if (admit()) {
                target.onReasoning(delta);
            }
        }

        private boolean admit() {
            return state.compareAndSet(WAITING, STREAMING) || state.get() == STREAMING;
        }

        /** 首片段超时：还没有片段时标记为超时并返回 true */
        boolean timeOut() {
            return state.compareAndSet(WAITING, TIMED_OUT);
        }

        boolean hasOutput() {
            return state.get() == STREAMING;
        }

        boolean isTimedOut() {
            return state.get() == TIMED_OUT;
        }
    }
}
