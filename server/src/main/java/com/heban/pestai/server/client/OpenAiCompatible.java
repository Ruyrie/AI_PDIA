package com.heban.pestai.server.client;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * 兼容 Chat Completions 格式的接口（DeepSeek、DashScope compatible-mode）的公共处理：
 * 构造请求体、逐行读取流式返回（每行 data: JSON，以 data: [DONE] 结束）、取出非流式返回的正文。
 */
public final class OpenAiCompatible {

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 上游请求与响应用的 ObjectMapper（字段名按接口原样书写，不做命名转换） */
    static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiCompatible() {
    }

    /** 构造请求体；带图消息的 content 为 [图片 1, 图片 2, …, 文字]（每张图片一个 image_url 部分，按顺序，文字在最后），其余为纯文本 */
    public static String buildRequest(String model, List<ChatMessage> messages, boolean stream,
                                      Double temperature, Integer maxTokens) {
        return buildRequest(model, messages, stream, temperature, maxTokens, null);
    }

    /** extra：各平台的扩展参数（如 DashScope 的 enable_thinking），原样写入请求体 */
    public static String buildRequest(String model, List<ChatMessage> messages, boolean stream,
                                      Double temperature, Integer maxTokens, java.util.Map<String, Object> extra) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        ArrayNode array = root.putArray("messages");
        for (ChatMessage message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.role());
            if (message.hasImage()) {
                ArrayNode content = node.putArray("content");
                for (String dataUrl : message.imageDataUrls()) {
                    ObjectNode image = content.addObject();
                    image.put("type", "image_url");
                    image.putObject("image_url").put("url", dataUrl);
                }
                ObjectNode text = content.addObject();
                text.put("type", "text");
                text.put("text", message.text() == null ? "" : message.text());
            } else {
                node.put("content", message.text() == null ? "" : message.text());
            }
        }
        root.put("stream", stream);
        if (temperature != null) {
            root.put("temperature", temperature);
        }
        if (maxTokens != null) {
            root.put("max_tokens", maxTokens);
        }
        if (extra != null) {
            extra.forEach((key, value) -> root.set(key, MAPPER.valueToTree(value)));
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("构造请求体失败", e);
        }
    }

    /**
     * 发起流式请求并逐行读取：跳过空行与注释行（如「: keep-alive」），data 行解析 JSON：
     * choices[0].delta.reasoning_content 非空时回调 onReasoning(片段)，choices[0].delta.content 非空时回调 onDelta(片段)。
     * 被 control 取消时关闭上游连接并安静返回。
     */
    public static void streamChat(OkHttpClient client, String url, String apiKey, String body,
                                  StreamCallback callback, StreamControl control) throws ChatClientException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(body, JSON))
                .build();
        Call call = client.newCall(request);
        control.onCancel(call::cancel);
        if (control.isCancelled()) {
            return;
        }
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new ChatClientException("上游返回 HTTP " + response.code() + "：" + errorSummary(response));
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new ChatClientException("上游响应体为空");
            }
            BufferedSource source = responseBody.source();
            boolean done = false;
            while (!control.isCancelled()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                String data = dataOf(line);
                if (data == null) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    done = true;
                    break;
                }
                JsonNode node = MAPPER.readTree(data);
                JsonNode error = node.get("error");
                if (error != null && !error.isNull()) {
                    throw new ChatClientException("上游返回错误：" + textOf(error.get("message")));
                }
                JsonNode choices = node.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    JsonNode deltaNode = choices.get(0).path("delta");
                    // 同一个分片同时带思考与正文时，先回调思考（思考总在正文之前）
                    String reasoning = textOf(deltaNode.get("reasoning_content"));
                    if (reasoning != null && !reasoning.isEmpty()) {
                        callback.onReasoning(reasoning);
                    }
                    String delta = textOf(deltaNode.get("content"));
                    if (delta != null && !delta.isEmpty()) {
                        callback.onDelta(delta);
                    }
                }
            }
            if (!done && !control.isCancelled()) {
                throw new ChatClientException("上游流意外结束（未收到 [DONE]）");
            }
        } catch (IOException e) {
            if (control.isCancelled()) {
                return;
            }
            throw new ChatClientException("读取上游失败：" + e.getMessage(), e);
        }
    }

    /** 「data: xxx」→「xxx」；不是 data 行返回 null */
    static String dataOf(String line) {
        if (line == null || !line.startsWith("data:")) {
            return null;
        }
        return line.substring(5).trim();
    }

    /** 非流式响应：取 choices[0].message.content（字符串，或 [{type:text,text}] 数组） */
    public static String extractContent(String responseJson) throws IOException {
        JsonNode root = MAPPER.readTree(responseJson);
        JsonNode content = root.path("choices").path(0).path("message").get("content");
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String text = textOf(part.get("text"));
                if (text != null) {
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    static String textOf(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String errorSummary(Response response) {
        try {
            ResponseBody body = response.body();
            if (body == null) {
                return "";
            }
            String text = body.string();
            return text.length() > 300 ? text.substring(0, 300) : text;
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
