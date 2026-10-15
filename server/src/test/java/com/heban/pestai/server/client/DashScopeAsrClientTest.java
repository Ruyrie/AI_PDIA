package com.heban.pestai.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.heban.pestai.server.service.WavAudio;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 用 MockWebServer 模拟 DashScope 兼容接口：请求体格式、两种 content 形式的解析、错误与超时。 */
class DashScopeAsrClientTest {

    private MockWebServer server;
    private DashScopeAsrClient client;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new DashScopeAsrClient(server.url("/compatible-mode/v1/chat/completions").toString(),
                () -> "sk-asr", () -> "qwen3-asr-flash", new OkHttpClient());
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsInputAudioDataUrlWithAsrOptions() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"番茄叶子发黄是什么原因\","
                        + "\"annotations\":[{\"type\":\"audio_info\",\"language\":\"zh\"}]}}]}"));
        byte[] wav = WavAudio.silence(16000, 200);

        assertThat(client.transcribe(wav, Duration.ofSeconds(5))).isEqualTo("番茄叶子发黄是什么原因");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/compatible-mode/v1/chat/completions");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer sk-asr");
        JsonNode body = new ObjectMapper().readTree(request.getBody().readUtf8());
        assertThat(body.path("model").asText()).isEqualTo("qwen3-asr-flash");
        assertThat(body.path("stream").asBoolean(true)).isFalse();
        JsonNode message = body.path("messages").path(0);
        assertThat(message.path("role").asText()).isEqualTo("user");
        JsonNode part = message.path("content").path(0);
        assertThat(part.path("type").asText()).isEqualTo("input_audio");
        assertThat(part.path("input_audio").path("data").asText())
                .isEqualTo("data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav));
        assertThat(body.path("asr_options").path("enable_itn").asBoolean()).isTrue();
        assertThat(body.path("asr_options").path("language").asText()).isEqualTo("zh");
    }

    @Test
    void arrayContentIsJoinedAndMissingContentIsEmpty() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"choices\":[{\"message\":{\"content\":[{\"text\":\"黄瓜\"},{\"type\":\"text\",\"text\":\"白粉病\"}]}}]}"));
        assertThat(client.transcribe(new byte[]{1}, Duration.ofSeconds(5))).isEqualTo("黄瓜白粉病");

        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":null}}]}"));
        assertThat(client.transcribe(new byte[]{1}, Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    void httpErrorBadJsonAndTimeoutAreAsrExceptions() {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"error\":{\"message\":\"Incorrect API key provided.\"}}"));
        assertThatThrownBy(() -> client.transcribe(new byte[]{1}, Duration.ofSeconds(5)))
                .isInstanceOf(AsrException.class).hasMessageContaining("401");

        server.enqueue(new MockResponse().setBody("<html>bad gateway</html>"));
        assertThatThrownBy(() -> client.transcribe(new byte[]{1}, Duration.ofSeconds(5)))
                .isInstanceOf(AsrException.class).hasMessageContaining("无法解析");

        server.enqueue(new MockResponse().setBody("{}").setHeadersDelay(2, TimeUnit.SECONDS));
        assertThatThrownBy(() -> client.transcribe(new byte[]{1}, Duration.ofMillis(300)))
                .isInstanceOf(AsrException.class).hasMessageContaining("超时");
    }
}
