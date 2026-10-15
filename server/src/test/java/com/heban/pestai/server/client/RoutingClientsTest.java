package com.heban.pestai.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.heban.pestai.server.common.Mode;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.config.ChatProperties;
import com.heban.pestai.server.config.PestaiProperties;
import com.heban.pestai.server.config.SmsProperties;
import com.heban.pestai.server.config.VisionProperties;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * 每次调用时按当前 Key 选择真实实现或 Mock（运行中在后台添加 / 删除 Key 不用重启），
 * 真实实现每次请求读取当前的 Key 与模型名。
 */
class RoutingClientsTest {

    private MockWebServer server;
    private final OkHttpClient http = new OkHttpClient();
    private final AtomicReference<String> key = new AtomicReference<>("");
    private final AtomicReference<String> model = new AtomicReference<>("qwen3.8-max");

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private static PestaiProperties.Mock fastMock() {
        PestaiProperties.Mock mock = new PestaiProperties.Mock();
        mock.setVisionDelayMs(0);
        mock.setImageBadPercent(0);
        mock.setChatChunkDelayMs(0);
        return mock;
    }

    @Test
    void visionSwitchesBetweenMockAndRealPerCallAndReadsKeyAndModelPerRequest() throws Exception {
        VisionProperties props = new VisionProperties();
        props.setUrl(server.url("/v1/chat/completions").toString());
        RoutingVisionClient client = new RoutingVisionClient(key::get,
                new DashScopeVisionClient(props, http, key::get, model::get), new MockVisionClient(fastMock()));

        assertThat(client.isMock()).isTrue();
        String mockResult = client.recognize(new byte[]{1, 2, 3}, "image/jpeg", Mode.PEST, List.of(), Duration.ofSeconds(5));
        assertThat(mockResult).contains("SUCCESS");
        assertThat(server.getRequestCount()).isZero();

        key.set("sk-added-at-runtime");
        model.set("qwen-vl-admin");
        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"));
        assertThat(client.isMock()).isFalse();
        assertThat(client.recognize(new byte[]{1}, "image/jpeg", Mode.PEST, List.of(), Duration.ofSeconds(5)))
                .isEqualTo("{}");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer sk-added-at-runtime");
        JsonNode body = new ObjectMapper().readTree(request.getBody().readUtf8());
        assertThat(body.path("model").asText()).isEqualTo("qwen-vl-admin");

        key.set("   ");
        assertThat(client.isMock()).isTrue();
    }

    @Test
    void chatSwitchesPerCallAndSupportsImagesFollowsCurrentImplementation() throws Exception {
        ChatProperties props = new ChatProperties();
        props.setUrl(server.url("/chat/completions").toString());
        props.setImageInput(false);
        AtomicReference<String> chatModel = new AtomicReference<>("deepseek-flash");
        RoutingChatClient client = new RoutingChatClient(key::get,
                new DeepSeekChatClient(props, http, key::get, chatModel::get), new MockChatClient(fastMock()));

        assertThat(client.isMock()).isTrue();
        assertThat(client.supportsImages()).isTrue();
        StringBuilder answer = new StringBuilder();
        client.stream(List.of(ChatMessage.user("蚜虫怎么防治")), answer::append, new StreamControl());
        assertThat(answer).isNotEmpty();
        assertThat(server.getRequestCount()).isZero();

        key.set("sk-chat");
        chatModel.set("deepseek-v4-pro");
        assertThat(client.isMock()).isFalse();
        assertThat(client.supportsImages()).isFalse();
        server.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"答\"}}]}\n\ndata: [DONE]\n\n"));
        StringBuilder real = new StringBuilder();
        client.stream(List.of(ChatMessage.user("q")), real::append, new StreamControl());
        assertThat(real.toString()).isEqualTo("答");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer sk-chat");
        assertThat(new ObjectMapper().readTree(request.getBody().readUtf8()).path("model").asText())
                .isEqualTo("deepseek-v4-pro");
    }

    @Test
    void asrSwitchesPerCall() throws Exception {
        RoutingAsrClient client = new RoutingAsrClient(key::get,
                new DashScopeAsrClient(server.url("/v1/chat/completions").toString(), key::get, () -> "qwen3-asr-flash", http),
                new MockAsrClient());
        assertThat(client.transcribe(new byte[]{1}, Duration.ofSeconds(5))).isEqualTo(MockAsrClient.MOCK_TEXT);
        assertThat(client.isMock()).isTrue();

        key.set("sk-asr");
        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\" 黄瓜叶子有白粉 \"}}]}"));
        assertThat(client.isMock()).isFalse();
        assertThat(client.transcribe(new byte[]{1}, Duration.ofSeconds(5))).isEqualTo("黄瓜叶子有白粉");
    }

    @Test
    void smsAutoSwitchesToAliyunOnceConfigIsCompleteAndBack() throws Exception {
        AtomicReference<SmsProperties.Aliyun> config = new AtomicReference<>(new SmsProperties.Aliyun());
        config.get().setEndpoint(server.url("/").toString());
        RoutingSmsClient client = new RoutingSmsClient("auto", config::get,
                new AliyunSmsClient(config::get, http, Clock.systemUTC()), new LogSmsClient());

        assertThat(client.isLogOnly()).isTrue();
        client.sendCode("13800005678", SmsScene.LOGIN, "123456");
        assertThat(server.getRequestCount()).isZero();

        SmsProperties.Aliyun complete = new SmsProperties.Aliyun();
        complete.setEndpoint(server.url("/").toString());
        complete.setAccessKeyId("LTAI-runtime");
        complete.setAccessKeySecret("secret");
        complete.setSignName("恒创联众");
        config.set(complete);
        assertThat(client.isLogOnly()).isFalse();
        server.enqueue(new MockResponse().setBody("{\"Code\":\"OK\",\"Model\":{\"BizId\":\"b1\"}}"));
        client.sendCode("13800005678", SmsScene.LOGIN, "123456");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getRequestUrl().queryParameter("AccessKeyId")).isEqualTo("LTAI-runtime");
        assertThat(request.getRequestUrl().queryParameter("TemplateCode")).isEqualTo("100001");

        complete.setAccessKeySecret("");
        assertThat(client.isLogOnly()).isTrue();
    }

    @Test
    void smsProviderLogAndAliyunAreFixed() {
        SmsProperties.Aliyun empty = new SmsProperties.Aliyun();
        RoutingSmsClient log = new RoutingSmsClient("LOG", () -> empty, new LogSmsClient(), new LogSmsClient());
        assertThat(log.provider()).isEqualTo("log");
        assertThat(log.isLogOnly()).isTrue();

        RoutingSmsClient aliyun = new RoutingSmsClient("aliyun", () -> empty,
                new AliyunSmsClient(empty, http, Clock.systemUTC()), new LogSmsClient());
        assertThat(aliyun.isLogOnly()).isFalse();
        // 配置不齐全时不会悄悄改为只打印日志
        assertThatThrownBy(() -> aliyun.sendCode("13800005678", SmsScene.LOGIN, "123456"))
                .isInstanceOf(SmsClient.SmsSendException.class);
        assertThat(RoutingSmsClient.normalize(null)).isEqualTo("auto");
        assertThat(RoutingSmsClient.normalize("whatever")).isEqualTo("auto");
    }
}
