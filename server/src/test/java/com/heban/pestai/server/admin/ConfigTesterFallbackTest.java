package com.heban.pestai.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.heban.pestai.server.admin.vo.ConfigTestVO;
import com.heban.pestai.server.client.AsrClient;
import com.heban.pestai.server.client.SmsClient;
import com.heban.pestai.server.config.ChatProperties;
import com.heban.pestai.server.config.ConfigCipher;
import com.heban.pestai.server.config.RuntimeConfigService;
import com.heban.pestai.server.config.VisionProperties;
import com.heban.pestai.server.mapper.SystemConfigMapper;
import com.heban.pestai.server.service.SmsService;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 配置测试的 fallback 分组：只测试已配置 Key 的备用模型，参数与正式调用一致；用 MockWebServer 模拟上游。 */
class ConfigTesterFallbackTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String OK_STREAM = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n";

    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private ConfigTester tester(MockEnvironment environment) {
        SystemConfigMapper mapper = mock(SystemConfigMapper.class);
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 10, 14, 9, 0).atZone(ZONE).toInstant(), ZONE);
        RuntimeConfigService runtimeConfig = new RuntimeConfigService(mapper, environment, new ConfigCipher("k", "j"), clock);
        VisionProperties vision = new VisionProperties();
        vision.setUrl(server.url("/dashscope/chat/completions").toString());
        ChatProperties chat = new ChatProperties();
        chat.setUrl(server.url("/deepseek/chat/completions").toString());
        return new ConfigTester(runtimeConfig, vision, chat, mock(AsrClient.class), mock(SmsClient.class),
                mock(SmsService.class), new OkHttpClient());
    }

    @Test
    void testsBothFallbackModelsWithTheirProductionParameters() throws Exception {
        server.enqueue(new MockResponse().setBody(OK_STREAM));
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad key sk-deepseek-123456\"}}"));
        ConfigTester tester = tester(new MockEnvironment()
                .withProperty("vision.api-key", "sk-dashscope-123456")
                .withProperty("chat.api-key", "sk-deepseek-123456"));

        ConfigTestVO result = tester.test("fallback", null);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).startsWith("问答备用：连接正常（模型 qwen3.8-max）")
                .contains("识图备用：HTTP 401").doesNotContain("sk-deepseek-123456");
        ObjectMapper json = new ObjectMapper();
        RecordedRequest chatFallback = server.takeRequest();
        assertThat(chatFallback.getPath()).isEqualTo("/dashscope/chat/completions");
        assertThat(chatFallback.getHeader("Authorization")).isEqualTo("Bearer sk-dashscope-123456");
        JsonNode chatBody = json.readTree(chatFallback.getBody().readUtf8());
        assertThat(chatBody.path("model").asText()).isEqualTo("qwen3.8-max");
        assertThat(chatBody.path("enable_thinking").asBoolean()).isTrue();
        RecordedRequest visionFallback = server.takeRequest();
        assertThat(visionFallback.getPath()).isEqualTo("/deepseek/chat/completions");
        JsonNode visionBody = json.readTree(visionFallback.getBody().readUtf8());
        assertThat(visionBody.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(visionBody.path("reasoning_effort").asText()).isEqualTo("none");
    }

    @Test
    void onlyConfiguredKeysAreTestedAndTheSwitchStateIsMentioned() throws Exception {
        server.enqueue(new MockResponse().setBody(OK_STREAM));
        ConfigTester tester = tester(new MockEnvironment()
                .withProperty("vision.api-key", "")
                .withProperty("chat.api-key", "sk-deepseek-123456")
                .withProperty("ai-fallback.enabled", "false"));

        ConfigTestVO result = tester.test("fallback", null);

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("问答备用：未配置 DASHSCOPE_API_KEY")
                .contains("识图备用：连接正常（模型 deepseek-flash）").contains("AI_FALLBACK_ENABLED 当前为 false");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void withoutAnyKeyNothingIsSent() {
        ConfigTester tester = tester(new MockEnvironment()
                .withProperty("vision.api-key", "")
                .withProperty("chat.api-key", ""));
        ConfigTestVO result = tester.test("fallback", null);
        assertThat(result.ok()).isFalse();
        assertThat(result.latencyMs()).isZero();
        assertThat(result.message()).endsWith("没有发出请求");
        assertThat(server.getRequestCount()).isZero();
        // 其他分组不受影响
        assertThat(List.of("vision", "chat", "asr", "sms", "fallback"))
                .allMatch(com.heban.pestai.server.config.RuntimeConfigItem.Group::isValid);
    }
}
