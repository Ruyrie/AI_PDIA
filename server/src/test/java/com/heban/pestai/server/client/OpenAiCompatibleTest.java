package com.heban.pestai.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.heban.pestai.server.common.Mode;
import com.heban.pestai.server.config.ChatProperties;
import com.heban.pestai.server.config.VisionProperties;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 用 MockWebServer 模拟上游大模型接口，不访问真实 API。 */
class OpenAiCompatibleTest {

    private MockWebServer server;
    private final OkHttpClient http = new OkHttpClient();

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void deepSeekStreamIsReadLineByLine() throws Exception {
        String body = ": keep-alive\n\n"
                + "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先看病斑形态\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"再看分布\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"从照片看，\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"病斑已连成片。\\n处理建议\"}}]}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body));

        ChatProperties props = new ChatProperties();
        props.setUrl(server.url("/chat/completions").toString());
        props.setApiKey("sk-test");
        DeepSeekChatClient client = new DeepSeekChatClient(props, http);

        List<String> deltas = new ArrayList<>();
        List<String> reasoning = new ArrayList<>();
        client.stream(List.of(ChatMessage.system("sys"), ChatMessage.user("问题")), new StreamCallback() {
            @Override
            public void onDelta(String delta) {
                deltas.add(delta);
            }

            @Override
            public void onReasoning(String delta) {
                reasoning.add(delta);
            }
        }, new StreamControl());

        // 思考片段原样传出（由 AiChatService 以 event: reasoning 推送给 App），正文片段不受影响
        assertThat(reasoning).containsExactly("先看病斑形态", "再看分布");
        assertThat(deltas).containsExactly("从照片看，", "病斑已连成片。\n处理建议");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer sk-test");
        JsonNode sent = new ObjectMapper().readTree(request.getBody().readUtf8());
        assertThat(sent.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(sent.path("stream").asBoolean()).isTrue();
        assertThat(sent.path("messages")).hasSize(2);
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(393216);
        assertThat(sent.path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(client.supportsImages()).isTrue();
    }

    @Test
    void reasoningComesBeforeContentInTheSameChunkAndLegacyCallbackStillWorks() throws Exception {
        String body = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"想\",\"content\":\"答\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"\",\"content\":null}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"再想\"}}]}\n\n"
                + "data: [DONE]\n\n";
        ChatProperties props = new ChatProperties();
        props.setUrl(server.url("/chat/completions").toString());
        props.setApiKey("sk-test");
        DeepSeekChatClient client = new DeepSeekChatClient(props, http);

        server.enqueue(new MockResponse().setBody(body));
        List<String> events = new ArrayList<>();
        client.stream(List.of(ChatMessage.user("q")), new StreamCallback() {
            @Override
            public void onDelta(String delta) {
                events.add("content:" + delta);
            }

            @Override
            public void onReasoning(String delta) {
                events.add("reasoning:" + delta);
            }
        }, new StreamControl());
        assertThat(events).containsExactly("reasoning:想", "content:答", "reasoning:再想");

        // 只实现旧的 onReasoning()（不关心思考内容）的回调仍按思考片段次数被调用
        server.enqueue(new MockResponse().setBody(body));
        int[] legacy = {0};
        StringBuilder answer = new StringBuilder();
        client.stream(List.of(ChatMessage.user("q")), new StreamCallback() {
            @Override
            public void onDelta(String delta) {
                answer.append(delta);
            }

            @Override
            public void onReasoning() {
                legacy[0]++;
            }
        }, new StreamControl());
        assertThat(legacy[0]).isEqualTo(2);
        assertThat(answer.toString()).isEqualTo("答");
    }

    @Test
    void non2xxAndTruncatedStreamsAreErrors() {
        ChatProperties props = new ChatProperties();
        props.setUrl(server.url("/chat/completions").toString());
        props.setApiKey("sk-test");
        DeepSeekChatClient client = new DeepSeekChatClient(props, http);

        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"invalid key\"}}"));
        assertThatThrownBy(() -> client.stream(List.of(ChatMessage.user("q")), d -> { }, new StreamControl()))
                .isInstanceOf(ChatClientException.class)
                .hasMessageContaining("401");

        server.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"半截\"}}]}\n\n"));
        assertThatThrownBy(() -> client.stream(List.of(ChatMessage.user("q")), d -> { }, new StreamControl()))
                .isInstanceOf(ChatClientException.class)
                .hasMessageContaining("DONE");
    }

    @Test
    void visionRecognizeSendsBase64ImageAndReturnsContent() throws Exception {
        String content = "{\\\"status\\\":\\\"IMAGE_BAD\\\"}";
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}"));

        VisionProperties props = new VisionProperties();
        props.setUrl(server.url("/compatible-mode/v1/chat/completions").toString());
        props.setApiKey("sk-vl");
        DashScopeVisionClient client = new DashScopeVisionClient(props, http);

        String raw = client.recognize(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}, "image/jpeg", Mode.PEST,
                List.of("番茄", "黄瓜"), Duration.ofSeconds(5));
        assertThat(raw).isEqualTo("{\"status\":\"IMAGE_BAD\"}");

        JsonNode sent = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
        assertThat(sent.path("model").asText()).isEqualTo("qwen3.8-max");
        assertThat(sent.path("enable_thinking").isBoolean()).isTrue();
        assertThat(sent.path("enable_thinking").asBoolean()).isFalse();
        assertThat(sent.path("stream").asBoolean()).isFalse();
        JsonNode user = sent.path("messages").path(1);
        assertThat(user.path("content").path(0).path("type").asText()).isEqualTo("image_url");
        assertThat(user.path("content").path(0).path("image_url").path("url").asText())
                .startsWith("data:image/jpeg;base64,/9j/");
        // 文字在图片之后：识图指令 + 一行作物参考信息
        assertThat(user.path("content").path(1).path("type").asText()).isEqualTo("text");
        assertThat(user.path("content").path(1).path("text").asText())
                .isEqualTo(VisionPrompts.userInstruction(List.of("番茄", "黄瓜")))
                .startsWith(VisionPrompts.USER_INSTRUCTION + "\n参考信息：该用户主要种植番茄、黄瓜。");
        assertThat(sent.path("messages").path(0).path("content").asText()).contains("top_name");
    }

    @Test
    void visionHttpErrorIsUnavailableAndSlowResponseIsTimeout() {
        VisionProperties props = new VisionProperties();
        props.setUrl(server.url("/v1/chat/completions").toString());
        props.setApiKey("sk-vl");
        DashScopeVisionClient client = new DashScopeVisionClient(props, http);

        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"rate limited\"}"));
        assertThatThrownBy(() -> client.recognize(new byte[]{1}, "image/jpeg", Mode.PEST, List.of(), Duration.ofSeconds(5)))
                .isInstanceOf(VisionException.class)
                .extracting(e -> ((VisionException) e).getReason())
                .isEqualTo(VisionException.Reason.UNAVAILABLE);

        server.enqueue(new MockResponse().setBody("{}").setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS));
        assertThatThrownBy(() -> client.recognize(new byte[]{1}, "image/jpeg", Mode.PEST, List.of(), Duration.ofMillis(300)))
                .isInstanceOf(VisionException.class)
                .extracting(e -> ((VisionException) e).getReason())
                .isEqualTo(VisionException.Reason.TIMEOUT);
    }

    @Test
    void multiImageMessageHasOneImagePartPerImageThenText() throws Exception {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("上一轮问题（用户附带了 2 张图片）"),
                ChatMessage.assistant("上一轮回答"),
                ChatMessage.user("这三张哪张更严重").withImages(List.of(
                        "data:image/jpeg;base64,AAAA", "data:image/png;base64,BBBB", "data:image/jpeg;base64,CCCC")));
        JsonNode sent = new ObjectMapper().readTree(
                OpenAiCompatible.buildRequest("deepseek-flash", messages, true, 0.7, null));

        JsonNode array = sent.path("messages");
        assertThat(array).hasSize(4);
        // 没有图片的消息仍为纯文本
        assertThat(array.path(1).path("content").isTextual()).isTrue();
        assertThat(array.path(2).path("content").asText()).isEqualTo("上一轮回答");
        JsonNode parts = array.path(3).path("content");
        assertThat(parts.isArray()).isTrue();
        assertThat(parts).hasSize(4);
        List<String> types = new ArrayList<>();
        parts.forEach(part -> types.add(part.path("type").asText()));
        assertThat(types).containsExactly("image_url", "image_url", "image_url", "text");
        assertThat(parts.path(0).path("image_url").path("url").asText()).isEqualTo("data:image/jpeg;base64,AAAA");
        assertThat(parts.path(1).path("image_url").path("url").asText()).isEqualTo("data:image/png;base64,BBBB");
        assertThat(parts.path(2).path("image_url").path("url").asText()).isEqualTo("data:image/jpeg;base64,CCCC");
        assertThat(parts.path(3).path("text").asText()).isEqualTo("这三张哪张更严重");

        // 单张图片（拍照识别、旧版单图提问）：[图片, 文字]
        JsonNode single = new ObjectMapper().readTree(OpenAiCompatible.buildRequest("m",
                List.of(ChatMessage.user("q").withImage("data:image/jpeg;base64,AA")), false, null, null));
        assertThat(single.path("messages").path(0).path("content")).hasSize(2);
        assertThat(ChatMessage.user("q").withImages(null).imageDataUrls()).isEmpty();
        assertThat(ChatMessage.user("q").hasImage()).isFalse();
    }

    @Test
    void extractContentHandlesStringAndArrayForms() throws Exception {
        assertThat(OpenAiCompatible.extractContent("{\"choices\":[{\"message\":{\"content\":\"abc\"}}]}")).isEqualTo("abc");
        assertThat(OpenAiCompatible.extractContent(
                "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"text\":\"b\"}]}}]}"))
                .isEqualTo("ab");
        assertThat(OpenAiCompatible.extractContent("{\"choices\":[]}")).isNull();
    }
}
