package com.heban.pestai.server.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 通义千问语音识别（默认 qwen3-asr-flash），DashScope 兼容 Chat Completions 接口（与识图相同的请求地址与 DASHSCOPE_API_KEY）：
 * <pre>
 * {"model":"qwen3-asr-flash",
 *  "messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"data:audio/wav;base64,..."}}]}],
 *  "stream":false,
 *  "asr_options":{"enable_itn":true,"language":"zh"}}
 * </pre>
 * enable_itn 把「二十三度」等口语数字转成「23 度」；language 指定中文以提高准确率（官方文档中兼容接口的顶层参数）。
 * 返回 choices[0].message.content（字符串或 [{text}] 数组）。Key 与模型名每次请求时取一次，后台修改后立即生效。
 */
public class DashScopeAsrClient implements AsrClient {

    static final String LANGUAGE = "zh";

    private final String url;
    private final Supplier<String> apiKey;
    private final Supplier<String> model;
    private final OkHttpClient baseClient;

    public DashScopeAsrClient(String url, Supplier<String> apiKey, Supplier<String> model, OkHttpClient baseClient) {
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
        this.baseClient = baseClient;
    }

    @Override
    public String transcribe(byte[] wav, Duration timeout) throws AsrException {
        OkHttpClient client = baseClient.newBuilder()
                .callTimeout(timeout)
                .readTimeout(timeout)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey.get())
                .post(RequestBody.create(buildRequest(model.get(), wav), OpenAiCompatible.JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            if (!response.isSuccessful()) {
                throw new AsrException("语音识别接口返回 HTTP " + response.code() + "：" + abbreviate(text));
            }
            String content;
            try {
                content = OpenAiCompatible.extractContent(text);
            } catch (IOException e) {
                throw new AsrException("语音识别接口返回的内容无法解析：" + abbreviate(text), e);
            }
            return content == null ? "" : content.trim();
        } catch (InterruptedIOException e) {
            throw new AsrException("语音识别超时", e);
        } catch (IOException e) {
            throw new AsrException("语音识别接口连接失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean isMock() {
        return false;
    }

    /** 请求体：一条 user 消息，内容只有一段 input_audio（WAV 的 data 地址） */
    static String buildRequest(String model, byte[] wav) {
        ObjectNode root = OpenAiCompatible.MAPPER.createObjectNode();
        root.put("model", model);
        ObjectNode message = root.putArray("messages").addObject();
        message.put("role", "user");
        ObjectNode audio = message.putArray("content").addObject();
        audio.put("type", "input_audio");
        audio.putObject("input_audio").put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav));
        root.put("stream", false);
        ObjectNode options = root.putObject("asr_options");
        options.put("enable_itn", true);
        options.put("language", LANGUAGE);
        try {
            return OpenAiCompatible.MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("构造请求体失败", e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
