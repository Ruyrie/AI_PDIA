package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.heban.pestai.server.client.ChatClient;
import com.heban.pestai.server.client.ChatClientException;
import com.heban.pestai.server.client.ChatMessage;
import com.heban.pestai.server.client.ChatPrompts;
import com.heban.pestai.server.client.FallbackBreaker;
import com.heban.pestai.server.client.FallbackChatClient;
import com.heban.pestai.server.client.StreamCallback;
import com.heban.pestai.server.client.StreamControl;
import com.heban.pestai.server.client.VisionClient;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ChatRole;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.config.JacksonConfig;
import com.heban.pestai.server.config.PestaiProperties;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiSession;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;
import com.heban.pestai.server.mapper.RecognitionMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.request.MessageRequest;

/**
 * 准备阶段的多图校验（最多 9 张、地址无效、只发图片、旧版 image_url）与生成阶段：思考片段以「event: reasoning」推送、
 * reasoning_ms 计算与保存、停止生成时保存已生成的思考与正文、识图路径的保活注释、会话中途被删除、
 * 回答所用模型与是否由备用模型回答（model / fallback）的保存。
 * 不连数据库（Mapper 全部 Mock），不调外部 API（上游为脚本化的假客户端），图片写在临时目录。
 */
class AiChatServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long SESSION_ID = 9L;
    private static final long USER_ID = 10001L;
    private static final String BASE = "http://10.0.2.2:8080";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 3};

    @TempDir
    Path root;

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
        session.setUserId(USER_ID);
        when(sessions.selectById(SESSION_ID)).thenReturn(session);
        chat = new ScriptedClient();
        vision = new ScriptedClient();
        vision.model = "qwen3.8-max";
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

    /** 准备阶段用：真实的文件存储（临时目录）与 PromptBuilder，保存问题的短事务 Mock */
    private AiMessageTxService preparingService(FileStorageService files) {
        service.shutdown();
        AiMessageTxService tx = mock(AiMessageTxService.class);
        when(tx.saveQuestion(any(), any(), any(), anyBoolean())).thenReturn(77L);
        service = new AiChatService(new AiRateLimiter(new PestaiProperties()), sessions, messages, tx,
                new PromptBuilder(messages, mock(RecognitionMapper.class), mock(UserMapper.class)), files, chat, vision,
                Runnable::run, JacksonConfig.newObjectMapper(), clock, new PestaiProperties());
        return tx;
    }

    private static MessageRequest request(String content, List<String> imageUrls, String legacyImageUrl) {
        MessageRequest request = new MessageRequest();
        request.setContent(content);
        request.setImageUrls(imageUrls);
        request.setImageUrl(legacyImageUrl);
        return request;
    }

    private static void assertParamError(Runnable call, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BizException.class)
                .hasMessage(message)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR);
    }

    @Test
    void imageOnlyQuestionWithSeveralImagesSendsAllImagesInOrder() {
        FileStorageService files = new FileStorageService(root, BASE, clock);
        AiMessageTxService tx = preparingService(files);
        String a = files.save(JPEG, FileStorageService.CHAT);
        String b = files.save(PNG, FileStorageService.CHAT);
        String c = files.save(JPEG, FileStorageService.CHAT);
        // 重复的地址（含带查询参数的写法）只算一张，顺序按第一次出现
        MessageRequest request = request("  ", List.of(files.toUrl(a), files.toUrl(b), " " + files.toUrl(a) + " ",
                files.toUrl(c), files.toUrl(b) + "?t=1"), null);

        AiChatService.PreparedChat prepared = service.prepare(USER_ID, SESSION_ID, request);

        // 只发图片：content 为空字符串，图片相对路径按顺序去重
        verify(tx).saveQuestion(any(), eq(""), eq(List.of(a, b, c)), eq(false));
        assertThat(prepared.useVision()).isTrue();
        assertThat(prepared.questionId()).isEqualTo(77L);
        ChatMessage current = prepared.messages().get(prepared.messages().size() - 1);
        assertThat(current.role()).isEqualTo(ChatRole.USER);
        assertThat(current.text()).isEqualTo(ChatPrompts.IMAGES_QUESTION);
        assertThat(current.imageDataUrls()).hasSize(3);
        assertThat(current.imageDataUrls().get(0)).startsWith("data:image/jpeg;base64,");
        assertThat(current.imageDataUrls().get(1)).startsWith("data:image/png;base64,");
        assertThat(current.imageDataUrls().get(2)).startsWith("data:image/jpeg;base64,");

        // 问答模型不能看图时改用识图模型，识图模型同样收到全部图片
        service.shutdown();
        ChatClient textOnly = new ScriptedClient() {
            @Override
            public boolean supportsImages() {
                return false;
            }
        };
        service = newService(textOnly);
        vision.script = (cb, control) -> cb.onDelta("三张照片都有霉层");
        service.run(prepared, new RecordingEmitter(), new StreamControl());
        assertThat(((ScriptedClient) textOnly).calls).isZero();
        assertThat(vision.received.get(vision.received.size() - 1).imageDataUrls())
                .isEqualTo(current.imageDataUrls());
    }

    @Test
    void legacySingleImageUrlStillWorksAndTextIsKept() {
        FileStorageService files = new FileStorageService(root, BASE, clock);
        AiMessageTxService tx = preparingService(files);
        String a = files.save(JPEG, FileStorageService.CHAT);

        AiChatService.PreparedChat prepared = service.prepare(USER_ID, SESSION_ID,
                request(" 叶子背面的黑点是什么 ", List.of(), files.toUrl(a)));

        verify(tx).saveQuestion(any(), eq("叶子背面的黑点是什么"), eq(List.of(a)), eq(false));
        ChatMessage current = prepared.messages().get(prepared.messages().size() - 1);
        assertThat(current.text()).isEqualTo("叶子背面的黑点是什么");
        assertThat(current.imageDataUrls()).hasSize(1);
        assertThat(prepared.useVision()).isTrue();

        // image_urls 非空时忽略旧字段 image_url
        String b = files.save(JPEG, FileStorageService.CHAT);
        service.prepare(USER_ID, SESSION_ID, request("", List.of(files.toUrl(b)), files.toUrl(a)));
        verify(tx).saveQuestion(any(), eq(""), eq(List.of(b)), eq(false));
    }

    @Test
    void textOnlyQuestionHasNoImages() {
        AiMessageTxService tx = preparingService(new FileStorageService(root, BASE, clock));
        AiChatService.PreparedChat prepared = service.prepare(USER_ID, SESSION_ID, request("蚜虫怎么防治", null, " "));
        verify(tx).saveQuestion(any(), eq("蚜虫怎么防治"), eq(List.of()), eq(false));
        assertThat(prepared.useVision()).isFalse();
        assertThat(prepared.messages().get(prepared.messages().size() - 1).hasImage()).isFalse();
    }

    @Test
    void moreThanNineImagesBlankQuestionAndInvalidImagesAreRejectedBeforeSaving() {
        FileStorageService files = new FileStorageService(root, BASE, clock);
        AiMessageTxService tx = preparingService(files);
        List<String> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(files.toUrl(files.save(JPEG, FileStorageService.CHAT)));
        }
        assertParamError(() -> service.prepare(USER_ID, SESSION_ID, request("看看", ten, null)), "最多上传 9 张图片");
        // 正好 9 张可以
        service.prepare(USER_ID, SESSION_ID, request("看看", ten.subList(0, 9), null));

        assertParamError(() -> service.prepare(USER_ID, SESSION_ID, request("  ", List.of(), null)), "请输入问题");
        assertParamError(() -> service.prepare(USER_ID, SESSION_ID, request(null, null, "")), "请输入问题");

        String good = ten.get(0);
        String missing = BASE + "/files/chat/20261014/missing.jpg";
        String rec = files.toUrl(files.save(JPEG, FileStorageService.REC));
        for (List<String> bad : List.of(List.of(good, missing), List.of(rec), List.of(good, " "),
                List.of("http://evil.example.com/a.jpg"))) {
            assertParamError(() -> service.prepare(USER_ID, SESSION_ID, request("看看", bad, null)),
                    "图片地址无效，请重新上传图片");
        }
        assertParamError(() -> service.prepare(USER_ID, SESSION_ID, request("看看", List.of(), missing)),
                "图片地址无效，请重新上传图片");
        // 只有正好 9 张的那一次保存了问题
        verify(tx).saveQuestion(any(), any(), any(), anyBoolean());
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

    @Test
    void answerRecordsThePrimaryModelWithoutFallback() {
        chat.script = (cb, control) -> cb.onDelta("答");
        service.run(prepared(false), new RecordingEmitter(), new StreamControl());
        AiMessage saved = savedAnswer();
        assertThat(saved.getModel()).isEqualTo("deepseek-flash");
        assertThat(saved.getFallback()).isFalse();
    }

    @Test
    void visionPathRecordsTheVisionModel() {
        service.shutdown();
        ScriptedClient textOnly = new ScriptedClient() {
            @Override
            public boolean supportsImages() {
                return false;
            }
        };
        service = newService(textOnly);
        vision.script = (cb, control) -> cb.onDelta("霉层");
        service.run(prepared(true), new RecordingEmitter(), new StreamControl());
        AiMessage saved = savedAnswer();
        assertThat(saved.getModel()).isEqualTo("qwen3.8-max");
        assertThat(saved.getFallback()).isFalse();
    }

    @Test
    void fallbackAnswerStreamsNormallyAndIsSavedWithTheFallbackModel() {
        ScriptedClient deepseek = new ScriptedClient();
        deepseek.isMockClient = false;
        deepseek.script = (cb, control) -> {
            throw new ChatClientException("上游返回 HTTP 503：busy");
        };
        ScriptedClient qwen = new ScriptedClient();
        qwen.model = "qwen3.8-max";
        qwen.script = (cb, control) -> {
            cb.onReasoning("看叶色");
            clock.advance(300);
            cb.onDelta("多半缺氮");
        };
        FallbackChatClient fallbackClient = new FallbackChatClient(deepseek, qwen,
                new FallbackBreaker(() -> true, () -> "sk-dashscope", clock, FallbackBreaker.OPEN_DURATION),
                Duration.ofSeconds(20));
        service.shutdown();
        service = newService(fallbackClient);
        RecordingEmitter emitter = new RecordingEmitter();
        try {
            service.run(prepared(false), emitter, new StreamControl());
        } finally {
            fallbackClient.shutdown();
        }

        // App 不感知：与正常回答完全相同，没有 [ERROR]
        assertThat(emitter.text()).isEqualTo("event: reasoning\ndata: 看叶色\n\ndata: 多半缺氮\n\ndata: [DONE]\n\n");
        AiMessage saved = savedAnswer();
        assertThat(saved.getContent()).isEqualTo("多半缺氮");
        assertThat(saved.getReasoningContent()).isEqualTo("看叶色");
        assertThat(saved.getModel()).isEqualTo("qwen3.8-max");
        assertThat(saved.getFallback()).isTrue();
        assertThat(deepseek.calls).isEqualTo(1);
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
        String model = "deepseek-flash";
        boolean isMockClient = true;
        /** 最近一次收到的 messages */
        List<ChatMessage> received = List.of();

        @Override
        public void stream(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
                throws ChatClientException {
            calls++;
            received = messages;
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
        public String recognize(byte[] image, String mimeType, String mode, List<String> userCrops,
                                java.time.Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isMock() {
            return isMockClient;
        }

        @Override
        public boolean supportsImages() {
            return true;
        }

        @Override
        public String modelName() {
            return model;
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
