package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.heban.pestai.server.client.ChatClient;
import com.heban.pestai.server.client.ChatClientException;
import com.heban.pestai.server.client.ChatMessage;
import com.heban.pestai.server.client.StreamCallback;
import com.heban.pestai.server.client.StreamControl;
import com.heban.pestai.server.client.VisionClient;
import com.heban.pestai.server.common.ChatRole;
import com.heban.pestai.server.config.JacksonConfig;
import com.heban.pestai.server.config.PestaiProperties;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiSession;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;

/**
 * 生成阶段：思考片段以「event: reasoning」推送、reasoning_ms 计算与保存、停止生成时保存已生成的思考与正文、
 * 识图路径的保活注释、会话中途被删除。不连数据库（Mapper 全部 Mock），不调外部 API（上游为脚本化的假客户端）。
 */
class AiChatServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long SESSION_ID = 9L;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-13T06:20:00Z"));
    private AiSessionMapper sessions;
    private AiMessageMapper messages;
    private ScriptedClient chat;
    private ScriptedClient vision;
    private AiChatService service;

    @BeforeEach
    void setUp() {
        sessions = mock(AiSessionMapper.class);
        messages = mock(AiMessageMapper.class);
        AiSession session = new AiSession();
        session.setId(SESSION_ID);
        session.setUserId(10001L);
        when(sessions.selectById(SESSION_ID)).thenReturn(session);
        chat = new ScriptedClient();
        vision = new ScriptedClient();
        service = newService(chat);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private AiChatService newService(ChatClient chatClient) {
        return new AiChatService(new AiRateLimiter(new PestaiProperties()), sessions, messages,
                mock(AiMessageTxService.class), mock(PromptBuilder.class), mock(FileStorageService.class),
                chatClient, vision, Runnable::run, JacksonConfig.newObjectMapper(), clock, new PestaiProperties());
    }

    private static AiChatService.PreparedChat prepared(boolean useVision) {
        return new AiChatService.PreparedChat(SESSION_ID, 77L, List.of(ChatMessage.user("问题")), useVision);
    }

    private AiMessage savedAnswer() {
        ArgumentCaptor<AiMessage> captor = ArgumentCaptor.forClass(AiMessage.class);
        verify(messages).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void reasoningIsPushedAsEventsAndSavedWithReasoningMs() {
        chat.script = (cb, control) -> {
            cb.onReasoning("先看病斑\n形态");
            clock.advance(800);
            cb.onReasoning("再看分布");
            clock.advance(400);
            cb.onDelta("初步判断为霜霉病");
            clock.advance(3000);
            cb.onDelta("\n处理建议");
        };
        RecordingEmitter emitter = new RecordingEmitter();
        service.run(prepared(false), emitter, new StreamControl());

        assertThat(emitter.text()).isEqualTo(
                "event: reasoning\ndata: 先看病斑\\n形态\n\n"
                        + "event: reasoning\ndata: 再看分布\n\n"
                        + "data: 初步判断为霜霉病\n\n"
                        + "data: \\n处理建议\n\n"
                        + "data: [DONE]\n\n");
        assertThat(emitter.completed).isTrue();

        AiMessage saved = savedAnswer();
        assertThat(saved.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(saved.getRole()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(saved.getContent()).isEqualTo("初步判断为霜霉病\n处理建议");
        assertThat(saved.getReasoningContent()).isEqualTo("先看病斑\n形态再看分布");
        // 从开始请求上游到第一个正文片段：800 + 400
        assertThat(saved.getReasoningMs()).isEqualTo(1200);
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.now(clock));

        // 写入回答后把会话 updated_at 更新为当前时间
        InOrder order = inOrder(sessions, messages);
        order.verify(sessions).selectById(SESSION_ID);
        order.verify(messages).insert(any());
        order.verify(sessions).touch(SESSION_ID, LocalDateTime.now(clock));
    }

    @Test
    void clientStopDuringAnswerSavesReasoningAndPartialAnswer() {
        chat.script = (cb, control) -> {
            cb.onReasoning("想一想");
            clock.advance(500);
            cb.onDelta("第一段");
            cb.onDelta("第二段");
            cb.onDelta("第三段");
        };
        // 第 3 次写出时客户端已断开（停止生成）
        RecordingEmitter emitter = new RecordingEmitter(3);
        StreamControl control = new StreamControl();
        service.run(prepared(false), emitter, control);

        assertThat(control.isCancelled()).isTrue();
        assertThat(emitter.text()).doesNotContain("[DONE]").doesNotContain("[ERROR]").doesNotContain("第三段");
        AiMessage saved = savedAnswer();
        assertThat(saved.getContent()).isEqualTo("第一段");
        assertThat(saved.getReasoningContent()).isEqualTo("想一想");
        assertThat(saved.getReasoningMs()).isEqualTo(500);
        verify(sessions).touch(eq(SESSION_ID), any());
    }

    @Test
    void clientStopDuringReasoningSavesReasoningWithEmptyContent() {
        chat.script = (cb, control) -> {
            cb.onReasoning("先判断是病害还是虫害");
            clock.advance(2000);
            // 客户端在正文开始前断开（SseEmitter.onError → control.cancel()）
            control.cancel();
        };
        RecordingEmitter emitter = new RecordingEmitter();
        service.run(prepared(false), emitter, new StreamControl());

        assertThat(emitter.text()).isEqualTo("event: reasoning\ndata: 先判断是病害还是虫害\n\n");
        AiMessage saved = savedAnswer();
        assertThat(saved.getContent()).isEmpty();
        assertThat(saved.getReasoningContent()).isEqualTo("先判断是病害还是虫害");
        assertThat(saved.getReasoningMs()).isNull();
    }

    @Test
    void upstreamErrorAfterReasoningSendsErrorAndKeepsGeneratedReasoning() {
        chat.script = (cb, control) -> {
            cb.onReasoning("思考中");
            throw new ChatClientException("上游流意外结束（未收到 [DONE]）");
        };
        RecordingEmitter emitter = new RecordingEmitter();
        service.run(prepared(false), emitter, new StreamControl());

        assertThat(emitter.text()).endsWith("data: [ERROR]{\"code\":50201,\"message\":\"AI 服务暂不可用，请稍后再试\"}\n\n");
        assertThat(savedAnswer().getReasoningContent()).isEqualTo("思考中");
    }

    @Test
    void visionPathWithoutReasoningKeepsFieldsNullAndSendsKeepAliveWhileSilent() {
        service.shutdown();
        ChatClient textOnly = new ScriptedClient() {
            @Override
            public boolean supportsImages() {
                return false;
            }
        };
        service = newService(textOnly);
        service.keepAliveMillis = 50;
        vision.script = (cb, control) -> {
            sleep(400);
            cb.onDelta("从照片看，叶片有霉层");
        };
        RecordingEmitter emitter = new RecordingEmitter();
        service.run(prepared(true), emitter, new StreamControl());

        // 上游沉默 400 ms 期间（保活间隔调为 50 ms）至少发出一次保活注释，其余输出与正常回答相同
        String text = emitter.text();
        assertThat(text).startsWith(":thinking\n\n");
        assertThat(text.replace(":thinking\n\n", "")).isEqualTo("data: 从照片看，叶片有霉层\n\ndata: [DONE]\n\n");
        AiMessage saved = savedAnswer();
        assertThat(saved.getContent()).isEqualTo("从照片看，叶片有霉层");
        assertThat(saved.getReasoningContent()).isNull();
        assertThat(saved.getReasoningMs()).isNull();
        assertThat(vision.calls).isEqualTo(1);
    }

    @Test
    void answerIsNotSavedWhenSessionWasDeletedDuringGeneration() {
        when(sessions.selectById(SESSION_ID)).thenReturn(null);
        chat.script = (cb, control) -> cb.onDelta("回答");
        RecordingEmitter emitter = new RecordingEmitter();
        service.run(prepared(false), emitter, new StreamControl());

        assertThat(emitter.text()).endsWith("data: [DONE]\n\n");
        verify(messages, never()).insert(any());
        verify(sessions, never()).touch(anyLong(), any());
    }

    @Test
    void nothingIsSavedWhenNoReasoningAndNoAnswer() {
        chat.script = (cb, control) -> control.cancel();
        service.run(prepared(false), new RecordingEmitter(), new StreamControl());
        verify(sessions, never()).touch(anyLong(), any());
        verify(messages, never()).insert(any());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 上游脚本：可以回调片段、推进时钟、取消或抛出异常 */
    @FunctionalInterface
    interface Script {
        void run(StreamCallback callback, StreamControl control) throws IOException, ChatClientException;
    }

    /**
     * 按脚本回调的假上游。与 OpenAiCompatible 一致：回调抛出 IOException（客户端已断开）且已取消时安静返回。
     */
    static class ScriptedClient implements ChatClient, VisionClient {

        Script script = (cb, control) -> {
        };
        int calls;

        @Override
        public void stream(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
                throws ChatClientException {
            calls++;
            try {
                script.run(callback, control);
            } catch (IOException e) {
                if (!control.isCancelled()) {
                    throw new ChatClientException("读取上游失败：" + e.getMessage(), e);
                }
            }
        }

        @Override
        public void streamChat(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
                throws ChatClientException {
            stream(messages, callback, control);
        }

        @Override
        public String recognize(byte[] image, String mimeType, String mode, java.time.Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isMock() {
            return true;
        }

        @Override
        public boolean supportsImages() {
            return true;
        }
    }

    /** 记录写出的 SSE 文本；failAt 为第几次写出时抛出 IOException（模拟客户端断开），0 表示不失败 */
    static class RecordingEmitter extends SseEmitter {

        private final StringBuffer out = new StringBuffer();
        private final int failAt;
        private int sends;
        volatile boolean completed;

        RecordingEmitter() {
            this(0);
        }

        RecordingEmitter(int failAt) {
            super(0L);
            this.failAt = failAt;
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            sends++;
            if (failAt > 0 && sends >= failAt) {
                throw new IOException("Broken pipe");
            }
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                out.append(part.getData());
            }
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }

        String text() {
            return out.toString();
        }
    }

    /** 可手动推进的时钟（Asia/Shanghai） */
    static class MutableClock extends Clock {

        private final AtomicLong millis;

        MutableClock(Instant start) {
            this.millis = new AtomicLong(start.toEpochMilli());
        }

        void advance(long delta) {
            millis.addAndGet(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }
}
