package com.heban.pestai.server.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.heban.pestai.server.client.ChatClient;
import com.heban.pestai.server.client.ChatClientException;
import com.heban.pestai.server.client.ChatMessage;
import com.heban.pestai.server.client.DashScopeVisionClient;
import com.heban.pestai.server.client.StreamCallback;
import com.heban.pestai.server.client.StreamControl;
import com.heban.pestai.server.client.VisionClient;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ChatRole;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.config.AiAsyncConfig;
import com.heban.pestai.server.config.PestaiProperties;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiSession;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;
import com.heban.pestai.server.request.MessageRequest;

/**
 * AI 问答（流式）。
 * <ol>
 *   <li>准备阶段（请求线程，同步）：限流 → 会话归属 → 图片地址校验 → 短事务保存问题 → 组装上下文 → 选择模型。
 *       任一步失败抛出 BizException，在流开始前以 application/json 的 ApiResponse 返回。</li>
 *   <li>生成阶段（aiStreamExecutor，异步，不持有事务）：思考片段写「event: reasoning」+「data: 片段」，
 *       正文片段写「data: 片段」（片段内换行都转义为 \n）；结束时先把完整回答（正文 + 思考过程 + 思考耗时）写入
 *       t_ai_message 再写「data: [DONE]」；出错写「data: [ERROR]{"code":50201,...}」后关闭；
 *       客户端断开（停止生成）时取消上游请求并保存已生成的思考与正文。</li>
 *   <li>保活：超过 5 秒没有写出任何内容（例如识图模型没有思考片段、正文开始前）时写 SSE 注释行「:thinking」，App 忽略。</li>
 * </ol>
 */
@Service
public class AiChatService {

    /** 超过这么久没有写出任何内容时，给客户端发一次保活注释 */
    static final long KEEP_ALIVE_MILLIS = 5000;
    /** 思考片段的 SSE 事件名 */
    public static final String REASONING_EVENT = "reasoning";

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    public static final String DONE = "[DONE]";
    public static final String ERROR = "[ERROR]";
    public static final String CHAT_UNAVAILABLE = "AI 服务暂不可用，请稍后再试";
    private static final MediaType TEXT_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final AiRateLimiter rateLimiter;
    private final AiSessionMapper aiSessionMapper;
    private final AiMessageMapper aiMessageMapper;
    private final AiMessageTxService txService;
    private final PromptBuilder promptBuilder;
    private final FileStorageService fileStorageService;
    private final ChatClient chatClient;
    private final VisionClient visionClient;
    private final Executor executor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final long timeoutMillis;
    /** 保活定时器：一个守护线程，各次生成共用 */
    private final ScheduledExecutorService keepAliveScheduler;
    /** 保活间隔（单元测试可调小） */
    long keepAliveMillis = KEEP_ALIVE_MILLIS;

    public AiChatService(AiRateLimiter rateLimiter, AiSessionMapper aiSessionMapper, AiMessageMapper aiMessageMapper,
                         AiMessageTxService txService, PromptBuilder promptBuilder,
                         FileStorageService fileStorageService, ChatClient chatClient, VisionClient visionClient,
                         @Qualifier(AiAsyncConfig.AI_STREAM_EXECUTOR) Executor executor,
                         ObjectMapper objectMapper, Clock clock, PestaiProperties properties) {
        this.rateLimiter = rateLimiter;
        this.aiSessionMapper = aiSessionMapper;
        this.aiMessageMapper = aiMessageMapper;
        this.txService = txService;
        this.promptBuilder = promptBuilder;
        this.fileStorageService = fileStorageService;
        this.chatClient = chatClient;
        this.visionClient = visionClient;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.timeoutMillis = properties.getAi().getStreamTimeoutSeconds() * 1000L;
        this.keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ai-keep-alive");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        keepAliveScheduler.shutdownNow();
    }

    /** 准备阶段：在请求线程同步执行，失败时以 JSON 返回 40001 / 42901 */
    public PreparedChat prepare(long userId, long sessionId, MessageRequest request) {
        rateLimiter.acquire(userId);
        AiSession session = aiSessionMapper.selectById(sessionId);
        if (session == null || session.getUserId() == null || session.getUserId() != userId) {
            throw BizException.param("会话不存在");
        }
        String content = request.getContent().strip();
        String imageRelative = null;
        String imageDataUrl = null;
        if (request.getImageUrl() != null && !request.getImageUrl().isBlank()) {
            imageRelative = fileStorageService.toRelative(request.getImageUrl(), FileStorageService.CHAT);
            if (imageRelative == null) {
                throw BizException.param("图片地址无效，请重新上传图片");
            }
            try {
                byte[] bytes = fileStorageService.readBytes(imageRelative);
                String mime = FileStorageService.detectMime(bytes);
                imageDataUrl = DashScopeVisionClient.toDataUrl(bytes,
                        mime != null ? mime : FileStorageService.mimeOfPath(imageRelative));
            } catch (IOException e) {
                throw BizException.param("图片地址无效，请重新上传图片");
            }
        }
        long questionId = txService.saveQuestion(session, content, imageRelative, request.isRegenerateRequested());
        List<ChatMessage> messages = promptBuilder.build(userId, session, questionId, content, imageDataUrl);
        return new PreparedChat(session.getId(), questionId, messages, imageDataUrl != null);
    }

    /** 创建 SseEmitter 并把生成任务提交到 aiStreamExecutor，立即返回 */
    public SseEmitter stream(PreparedChat prepared) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        StreamControl control = new StreamControl();
        emitter.onTimeout(() -> {
            log.info("流式回答达到总时长上限，按客户端断开处理（会话 {}）", prepared.sessionId());
            control.cancel();
            emitter.complete();
        });
        emitter.onError(e -> control.cancel());
        try {
            executor.execute(() -> run(prepared, emitter, control));
        } catch (RejectedExecutionException e) {
            log.warn("aiStreamExecutor 已满，拒绝本次生成（会话 {}）", prepared.sessionId());
            sendError(emitter);
            emitter.complete();
        }
        return emitter;
    }

    /** 生成阶段 */
    void run(PreparedChat prepared, SseEmitter emitter, StreamControl control) {
        ChatStream stream = new ChatStream(emitter, control);
        AtomicBoolean saved = new AtomicBoolean(false);
        ScheduledFuture<?> keepAlive = scheduleKeepAlive(stream);
        try {
            stream.startedAt = clock.millis();
            // 带图提问：问答模型能看图（deepseek-flash）就交给它，否则改用识图模型，保证图片一定被模型看到
            if (prepared.useVision() && !chatClient.supportsImages()) {
                visionClient.streamChat(prepared.messages(), stream, control);
            } else {
                chatClient.stream(prepared.messages(), stream, control);
            }
            stopKeepAlive(keepAlive);
            saveAnswer(prepared.sessionId(), stream, saved);
            if (control.isCancelled()) {
                log.info("客户端停止生成（会话 {}，已生成正文 {} 字、思考 {} 字）", prepared.sessionId(),
                        stream.answer.length(), stream.reasoning.length());
                completeQuietly(emitter);
                return;
            }
            sendRaw(emitter, " " + DONE);
            completeQuietly(emitter);
        } catch (ChatClientException e) {
            stopKeepAlive(keepAlive);
            saveAnswer(prepared.sessionId(), stream, saved);
            if (control.isCancelled()) {
                log.info("客户端停止生成（会话 {}）", prepared.sessionId());
            } else {
                log.warn("AI 回答生成失败（会话 {}）：{}", prepared.sessionId(), e.getMessage());
                sendError(emitter);
            }
            completeQuietly(emitter);
        } catch (RuntimeException e) {
            stopKeepAlive(keepAlive);
            log.error("AI 回答生成异常（会话 {}）", prepared.sessionId(), e);
            saveAnswer(prepared.sessionId(), stream, saved);
            if (!control.isCancelled()) {
                sendError(emitter);
            }
            completeQuietly(emitter);
        } finally {
            stopKeepAlive(keepAlive);
        }
    }

    /**
     * 保活：每隔 keepAliveMillis / 5 检查一次，距上次写出超过 keepAliveMillis 时写 SSE 注释行「:thinking」；
     * 写出失败说明客户端已断开，取消上游请求。生成结束（保存回答、写 [DONE] 之前）即取消。
     * 定时器已关闭（应用正在停止）时返回 null，本次生成不发保活。
     */
    private ScheduledFuture<?> scheduleKeepAlive(ChatStream stream) {
        long period = Math.max(10, keepAliveMillis / 5);
        long idleNanos = TimeUnit.MILLISECONDS.toNanos(keepAliveMillis);
        try {
            return keepAliveScheduler.scheduleWithFixedDelay(() -> sendKeepAlive(stream, idleNanos),
                    period, period, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    private static void stopKeepAlive(ScheduledFuture<?> keepAlive) {
        if (keepAlive != null) {
            keepAlive.cancel(false);
        }
    }

    /** 在保活线程执行；SseEmitter.send 内部加锁，不会与生成线程的写出交错 */
    private static void sendKeepAlive(ChatStream stream, long idleNanos) {
        if (stream.control.isCancelled() || System.nanoTime() - stream.lastSentNanos < idleNanos) {
            return;
        }
        try {
            stream.emitter.send(SseEmitter.event().comment("thinking"));
            stream.lastSentNanos = System.nanoTime();
        } catch (IOException | IllegalStateException e) {
            stream.control.cancel();
        } catch (RuntimeException e) {
            // 定时任务抛出异常会终止后续执行，这里只记日志
            log.debug("发送保活失败：{}", e.getMessage());
        }
    }

    /**
     * 回答（或被中断时已生成的部分）写入 t_ai_message；「已保存」标记保证只写一次。
     * 正文与思考都为空时不写库；只有思考（停止生成时正文还没开始）时 content 为空字符串、reasoning_ms 为空。
     * 会话已被删除（生成途中删除了该对话）时不再写入，避免留下没有会话的消息；写入后更新会话 updated_at。
     */
    private void saveAnswer(long sessionId, ChatStream stream, AtomicBoolean saved) {
        if ((stream.answer.length() == 0 && stream.reasoning.length() == 0) || !saved.compareAndSet(false, true)) {
            return;
        }
        try {
            if (aiSessionMapper.selectById(sessionId) == null) {
                log.info("会话 {} 已删除，不再保存本轮回答", sessionId);
                return;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            AiMessage message = new AiMessage(sessionId, ChatRole.ASSISTANT, stream.answer.toString(), null, now);
            if (stream.reasoning.length() > 0) {
                message.setReasoningContent(stream.reasoning.toString());
                message.setReasoningMs(stream.reasoningMillis());
            }
            aiMessageMapper.insert(message);
            aiSessionMapper.touch(sessionId, now);
        } catch (RuntimeException e) {
            log.error("保存 AI 回答失败（会话 {}）", sessionId, e);
        }
    }

    /**
     * 一次生成的回调与状态：把思考片段、正文片段写给客户端并累积，记录第一个正文片段到达的时间。
     * 回调都在生成线程中执行；lastSentNanos 还会被保活线程读写，用 volatile。
     */
    private final class ChatStream implements StreamCallback {

        final SseEmitter emitter;
        final StreamControl control;
        final StringBuilder answer = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        /** 开始请求上游的时间（clock 毫秒） */
        long startedAt;
        /** 收到第一个正文片段的时间（clock 毫秒），尚未收到为 -1 */
        long firstDeltaAt = -1;
        volatile long lastSentNanos = System.nanoTime();

        ChatStream(SseEmitter emitter, StreamControl control) {
            this.emitter = emitter;
            this.control = control;
        }

        @Override
        public void onDelta(String delta) throws IOException {
            if (firstDeltaAt < 0) {
                firstDeltaAt = clock.millis();
            }
            send(SseEmitter.event().data(" " + escape(delta), TEXT_UTF8));
            answer.append(delta);
        }

        /** 「event: reasoning」+「data: 片段」；事件名前加空格，与 data 行一样输出为「event: reasoning」 */
        @Override
        public void onReasoning(String delta) throws IOException {
            send(SseEmitter.event().name(" " + REASONING_EVENT).data(" " + escape(delta), TEXT_UTF8));
            reasoning.append(delta);
        }

        private void send(SseEmitter.SseEventBuilder event) throws IOException {
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException e) {
                // 客户端已断开（停止生成、断网）：取消上游请求
                control.cancel();
                throw e instanceof IOException io ? io : new IOException(e);
            }
            lastSentNanos = System.nanoTime();
        }

        /** 思考耗时：开始请求上游到第一个正文片段；正文还没开始（停止生成）时为 null */
        Integer reasoningMillis() {
            if (firstDeltaAt < 0) {
                return null;
            }
            long elapsed = Math.max(0, firstDeltaAt - startedAt);
            return (int) Math.min(Integer.MAX_VALUE, elapsed);
        }
    }

    private void sendError(SseEmitter emitter) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", ErrorCode.AI_UNAVAILABLE.getCode());
        error.put("message", CHAT_UNAVAILABLE);
        String json;
        try {
            json = objectMapper.writeValueAsString(error);
        } catch (JsonProcessingException e) {
            json = "{\"code\":50201,\"message\":\"" + CHAT_UNAVAILABLE + "\"}";
        }
        sendRaw(emitter, " " + ERROR + json);
    }

    private static void sendRaw(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().data(data, TEXT_UTF8));
        } catch (IOException | IllegalStateException e) {
            log.debug("写出失败（客户端可能已断开）：{}", e.getMessage());
        }
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("结束流式响应失败：{}", e.getMessage());
        }
    }

    /** 片段内的换行转义为 \n（否则一个片段会被拆成多行 data），去掉 \r */
    static String escape(String delta) {
        return delta.replace("\r", "").replace("\n", "\\n");
    }

    /** 准备阶段的结果 */
    public record PreparedChat(long sessionId, long questionId, List<ChatMessage> messages, boolean useVision) {
    }
}
