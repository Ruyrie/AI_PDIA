package com.heban.pestai.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.heban.pestai.server.config.ChatProperties;
import com.heban.pestai.server.config.PestaiProperties;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * AI 问答的自动备用（主 DeepSeek → 备通义千问）：只在任何片段之前失败（含首片段超时）时改用备用模型；已送出片段后出错保持原行为；
 * 熔断 60 秒后再试主模型；开关关闭、没有 Key、主模型是 Mock、客户端已断开时不改用。
 * 前半部分用脚本化的假客户端，后半部分用 MockWebServer 模拟真实的 DeepSeek / DashScope 接口，不访问网络。
 */
class FallbackChatClientTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T02:00:00Z"));
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicReference<String> dashscopeKey = new AtomicReference<>("sk-dashscope-test");
    private Scripted primary;
    private Scripted fallback;
    private FallbackChatClient client;

    @BeforeEach
    void setUp() {
        primary = new Scripted("deepseek-flash");
        fallback = new Scripted("qwen3.8-max");
        client = newClient(Duration.ofSeconds(20));
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
    }

    private FallbackChatClient newClient(Duration firstChunkTimeout) {
        if (client != null) {
            client.shutdown();
        }
        return new FallbackChatClient(primary, fallback,
                new FallbackBreaker(enabled::get, dashscopeKey::get, clock, FallbackBreaker.OPEN_DURATION), firstChunkTimeout);
    }

    private static List<ChatMessage> question() {
        return List.of(ChatMessage.system("sys"), ChatMessage.user("黄瓜叶子发黄怎么办"));
    }

    @Test
    void failureBeforeAnyChunkSwitchesToTheFallbackAndReportsTheModel() throws Exception {
        primary.script = (cb, control) -> {
            throw new ChatClientException("上游返回 HTTP 503：{\"error\":\"busy\"} key sk-abcdefgh12345678");
        };
        fallback.script = (cb, control) -> {
            cb.onReasoning("先看叶片");
            cb.onDelta("可能是缺氮");
        };
        Recorder recorder = new Recorder();

        client.stream(question(), recorder, new StreamControl());

        assertThat(recorder.events).containsExactly(
                "model:deepseek-flash:false", "model:qwen3.8-max:true", "reasoning:先看叶片", "delta:可能是缺氮");
        // 备用模型收到同样的消息；失败原因写入日志前遮盖了疑似密钥
        assertThat(fallback.received).isEqualTo(question());
        assertThat(recorder.reason).contains("deepseek-flash").contains("HTTP 503").contains("sk-****")
                .doesNotContain("abcdefgh12345678");
        assertThat(client.breaker().isOpen()).isTrue();
    }

    @Test
    void failureAfterTheFirstChunkKeepsTheOriginalBehaviour() {
        primary.script = (cb, control) -> {
            cb.onReasoning("思考中");
            throw new ChatClientException("上游流意外结束（未收到 [DONE]）");
        };
        Recorder recorder = new Recorder();

        assertThatThrownBy(() -> client.stream(question(), recorder, new StreamControl()))
                .isInstanceOf(ChatClientException.class).hasMessageContaining("[DONE]");
        assertThat(fallback.calls).isZero();
        assertThat(recorder.events).containsExactly("model:deepseek-flash:false", "reasoning:思考中");
        assertThat(client.breaker().isOpen()).isFalse();
    }

    @Test
    void noChunkWithinTheFirstChunkTimeoutCancelsThePrimaryAndSwitches() throws Exception {
        client = newClient(Duration.ofMillis(150));
        AtomicBoolean primaryCancelled = new AtomicBoolean();
        primary.script = (cb, control) -> {
            // 与 OkHttp 一样：一直等到被取消，再送来一个迟到的片段，然后安静返回
            CountDownLatch cancelled = new CountDownLatch(1);
            control.onCancel(cancelled::countDown);
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
            primaryCancelled.set(control.isCancelled());
            cb.onDelta("迟到的片段");
        };
        fallback.script = (cb, control) -> cb.onDelta("备用回答");
        Recorder recorder = new Recorder();
        StreamControl control = new StreamControl();

        long started = System.nanoTime();
        client.stream(question(), recorder, control);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(primaryCancelled).isTrue();
        // 只取消了主模型的请求，整次生成没有被取消；超时后迟到的片段不会送到客户端
        assertThat(control.isCancelled()).isFalse();
        assertThat(recorder.events).containsExactly("model:deepseek-flash:false", "model:qwen3.8-max:true", "delta:备用回答");
        assertThat(recorder.reason).contains("150 毫秒内没有返回任何内容");
    }

    @Test
    void firstChunkInTimeIsNotInterrupted() throws Exception {
        client = newClient(Duration.ofMillis(200));
        primary.script = (cb, control) -> {
            cb.onReasoning("想");
            // 第一个片段之后再慢也不会被首片段超时打断
            Thread.sleep(400);
            cb.onDelta("答");
        };
        Recorder recorder = new Recorder();
        client.stream(question(), recorder, new StreamControl());
        assertThat(recorder.events).containsExactly("model:deepseek-flash:false", "reasoning:想", "delta:答");
        assertThat(fallback.calls).isZero();
    }

    @Test
    void breakerRoutesStraightToTheFallbackFor60SecondsThenTriesThePrimaryAgain() throws Exception {
        primary.script = (cb, control) -> {
            throw new ChatClientException("读取上游失败：Connection refused");
        };
        fallback.script = (cb, control) -> cb.onDelta("备用");
        client.stream(question(), new Recorder(), new StreamControl());
        assertThat(primary.calls).isEqualTo(1);

        // 59 秒内：不再先试主模型
        clock.advance(59_000);
        Recorder direct = new Recorder();
        client.stream(question(), direct, new StreamControl());
        assertThat(primary.calls).isEqualTo(1);
        assertThat(fallback.calls).isEqualTo(2);
        assertThat(direct.events).containsExactly("model:qwen3.8-max:true", "delta:备用");
        assertThat(direct.reason).contains("熔断中");

        // 60 秒后：再试主模型；主模型恢复则不再使用备用
        clock.advance(1_000);
        primary.script = (cb, control) -> cb.onDelta("主模型");
        Recorder recovered = new Recorder();
        client.stream(question(), recovered, new StreamControl());
        assertThat(primary.calls).isEqualTo(2);
        assertThat(fallback.calls).isEqualTo(2);
        assertThat(recovered.events).containsExactly("model:deepseek-flash:false", "delta:主模型");
        assertThat(client.breaker().isOpen()).isFalse();
    }

    @Test
    void noFallbackWhenDisabledWithoutKeyOrWhenThePrimaryIsMock() {
        primary.script = (cb, control) -> {
            throw new ChatClientException("上游返回 HTTP 500：");
        };

        enabled.set(false);
        assertThatThrownBy(() -> client.stream(question(), new Recorder(), new StreamControl()))
                .isInstanceOf(ChatClientException.class);

        enabled.set(true);
        dashscopeKey.set("");
        assertThatThrownBy(() -> client.stream(question(), new Recorder(), new StreamControl()))
                .isInstanceOf(ChatClientException.class);

        dashscopeKey.set("sk-dashscope-test");
        primary.mock = true;
        Recorder recorder = new Recorder();
        assertThatThrownBy(() -> client.stream(question(), recorder, new StreamControl()))
                .isInstanceOf(ChatClientException.class);
        assertThat(recorder.events).containsExactly("model:mock:false");

        assertThat(fallback.calls).isZero();
        assertThat(client.breaker().isOpen()).isFalse();
    }

    @Test
    void withoutFallbackThereIsNoFirstChunkTimeout() throws Exception {
        client = newClient(Duration.ofMillis(50));
        enabled.set(false);
        primary.script = (cb, control) -> {
            Thread.sleep(250);
            cb.onDelta("慢但完整的回答");
        };
        Recorder recorder = new Recorder();
        client.stream(question(), recorder, new StreamControl());
        assertThat(recorder.events).containsExactly("model:deepseek-flash:false", "delta:慢但完整的回答");
    }

    @Test
    void clientDisconnectBeforeTheFirstChunkDoesNotFallBack() throws Exception {
        StreamControl control = new StreamControl();
        // 客户端断开（control 取消）后主模型安静返回
        primary.script = (cb, c) -> control.cancel();
        client.stream(question(), new Recorder(), control);
        assertThat(fallback.calls).isZero();

        // 断开的同时上游报错：异常交给调用方（AiChatService 据 control 判断为停止生成），也不改用备用
        StreamControl control2 = new StreamControl();
        primary.script = (cb, c) -> {
            control2.cancel();
            throw new ChatClientException("读取上游失败：Socket closed");
        };
        assertThatThrownBy(() -> client.stream(question(), new Recorder(), control2))
                .isInstanceOf(ChatClientException.class);
        assertThat(fallback.calls).isZero();
        assertThat(client.breaker().isOpen()).isFalse();
    }

    @Test
    void imagesFollowThePrimaryAndModelNameIsThePrimarys() {
        primary.images = false;
        assertThat(client.supportsImages()).isFalse();
        primary.images = true;
        assertThat(client.supportsImages()).isTrue();
        assertThat(client.modelName()).isEqualTo("deepseek-flash");
        assertThat(client.isMock()).isFalse();
    }

    // ------------------------------------------------------------------ 真实客户端 + MockWebServer

    @Test
    void deepSeekStreamEndingWithoutDoneFallsBackToQwenWithThinking() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            // DeepSeek：只有一个没有内容的分片就断开（没有 [DONE]）
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n"));
            // 通义千问：思考 + 正文
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"看叶色\"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{\"content\":\"多半缺氮\"}}]}\n\n"
                            + "data: [DONE]\n\n"));
            FallbackChatClient real = realClient(server);
            Recorder recorder = new Recorder();
            try {
                real.stream(List.of(ChatMessage.user("问题").withImage("data:image/jpeg;base64,AAAA")), recorder,
                        new StreamControl());
            } finally {
                real.shutdown();
            }

            assertThat(recorder.events).containsExactly("model:deepseek-flash:false", "model:qwen3.8-max:true",
                    "reasoning:看叶色", "delta:多半缺氮");
            assertThat(recorder.reason).contains("[DONE]");
            RecordedRequest deepseek = server.takeRequest();
            assertThat(deepseek.getPath()).isEqualTo("/deepseek/chat/completions");
            assertThat(deepseek.getHeader("Authorization")).isEqualTo("Bearer sk-deepseek");
            RecordedRequest qwen = server.takeRequest();
            assertThat(qwen.getPath()).isEqualTo("/dashscope/chat/completions");
            assertThat(qwen.getHeader("Authorization")).isEqualTo("Bearer sk-dashscope-test");
            JsonNode body = new ObjectMapper().readTree(qwen.getBody().readUtf8());
            assertThat(body.path("model").asText()).isEqualTo("qwen3.8-max");
            assertThat(body.path("stream").asBoolean()).isTrue();
            assertThat(body.path("enable_thinking").asBoolean()).isTrue();
            // DeepSeek 的 max_tokens 上限不传给通义千问；图片照常发送
            assertThat(body.has("max_tokens")).isFalse();
            assertThat(body.path("messages").get(0).path("content").get(0).path("type").asText()).isEqualTo("image_url");
        } finally {
            server.shutdown();
        }
    }

    @Test
    void deepSeekHttpErrorFallsBackButQwenErrorIsReturned() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"Server busy\"}}"));
            server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad key\"}}"));
            FallbackChatClient real = realClient(server);
            Recorder recorder = new Recorder();
            try {
                assertThatThrownBy(() -> real.stream(List.of(ChatMessage.user("q")), recorder, new StreamControl()))
                        .isInstanceOf(ChatClientException.class).hasMessageContaining("HTTP 401");
            } finally {
                real.shutdown();
            }
            assertThat(recorder.reason).contains("HTTP 503").contains("Server busy");
            assertThat(server.getRequestCount()).isEqualTo(2);
        } finally {
            server.shutdown();
        }
    }

    private FallbackChatClient realClient(MockWebServer server) {
        OkHttpClient http = new OkHttpClient();
        ChatProperties props = new ChatProperties();
        props.setUrl(server.url("/deepseek/chat/completions").toString());
        PestaiProperties.Mock mock = new PestaiProperties.Mock();
        mock.setChatChunkDelayMs(0);
        RoutingChatClient deepseek = new RoutingChatClient(() -> "sk-deepseek",
                new DeepSeekChatClient(props, http, () -> "sk-deepseek", () -> "deepseek-flash"), new MockChatClient(mock));
        QwenChatClient qwen = new QwenChatClient(server.url("/dashscope/chat/completions").toString(), props, http,
                dashscopeKey::get, () -> "qwen3.8-max");
        return new FallbackChatClient(deepseek, qwen,
                new FallbackBreaker(enabled::get, dashscopeKey::get, clock, FallbackBreaker.OPEN_DURATION),
                Duration.ofSeconds(20));
    }

    // ------------------------------------------------------------------ 测试工具

    @FunctionalInterface
    interface Script {
        void run(StreamCallback callback, StreamControl control) throws Exception;
    }

    /** 按脚本回调的假上游；回调抛出 IOException 且已取消时与 OpenAiCompatible 一样安静返回 */
    static final class Scripted implements ChatClient {

        final String model;
        Script script = (cb, control) -> {
        };
        int calls;
        boolean mock;
        boolean images = true;
        List<ChatMessage> received;

        Scripted(String model) {
            this.model = model;
        }

        @Override
        public void stream(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
                throws ChatClientException {
            calls++;
            received = messages;
            try {
                script.run(callback, control);
            } catch (ChatClientException e) {
                throw e;
            } catch (IOException e) {
                if (!control.isCancelled()) {
                    throw new ChatClientException("读取上游失败：" + e.getMessage(), e);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean isMock() {
            return mock;
        }

        @Override
        public boolean supportsImages() {
            return images;
        }

        @Override
        public String modelName() {
            return mock ? MockChatClient.MODEL_NAME : model;
        }
    }

    /** 记录回调的顺序：model:名称:是否备用、reasoning:片段、delta:片段 */
    static final class Recorder implements StreamCallback {

        final List<String> events = new ArrayList<>();
        String reason;

        @Override
        public synchronized void onDelta(String delta) {
            events.add("delta:" + delta);
        }

        @Override
        public synchronized void onReasoning(String delta) {
            events.add("reasoning:" + delta);
        }

        @Override
        public synchronized void onModel(String model, boolean fallback, String reason) {
            events.add("model:" + model + ":" + fallback);
            if (fallback) {
                this.reason = reason;
            }
        }
    }

    static final class MutableClock extends Clock {

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
