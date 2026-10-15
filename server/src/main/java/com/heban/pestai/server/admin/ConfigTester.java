package com.heban.pestai.server.admin;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.heban.pestai.server.admin.vo.ConfigTestVO;
import com.heban.pestai.server.client.AsrClient;
import com.heban.pestai.server.client.AsrException;
import com.heban.pestai.server.client.ChatMessage;
import com.heban.pestai.server.client.DeepSeekVisionClient;
import com.heban.pestai.server.client.OpenAiCompatible;
import com.heban.pestai.server.client.RoutingAsrClient;
import com.heban.pestai.server.client.RoutingSmsClient;
import com.heban.pestai.server.client.SmsClient;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.Rules;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.config.ChatProperties;
import com.heban.pestai.server.config.RuntimeConfigItem;
import com.heban.pestai.server.config.RuntimeConfigService;
import com.heban.pestai.server.config.VisionProperties;
import com.heban.pestai.server.service.SmsService;
import com.heban.pestai.server.service.WavAudio;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * 配置测试（POST admin/api/config/test/{group}）：用当前生效的 Key 与模型发一次最小请求，报告是否成功、耗时与简短原因。
 * <ul>
 *   <li>vision：向识图模型发一句纯文字「你好」（流式，max_tokens 16，不传图片，收到第一个片段即算连接正常）；</li>
 *   <li>chat：向问答模型发一句「你好」（流式，max_tokens 16，收到第一个片段即算连接正常，不等深度思考结束）；</li>
 *   <li>asr：上传 0.5 秒静音 WAV（识别结果为空也算成功，说明 Key 与模型可用）；</li>
 *   <li>sms：走正常的 SmsService 发送一条登录验证码（同手机号 60 秒限频照常生效）；短信当前只打印日志时不发送，直接说明原因；</li>
 *   <li>fallback（自动备用）：依次测试已配置 Key 的备用模型 —— 问答备用（CHAT_FALLBACK_MODEL，DASHSCOPE_API_KEY，
 *       与正式调用一样开启深度思考）、识图备用（VISION_FALLBACK_MODEL，DEEPSEEK_API_KEY，reasoning_effort none），
 *       请求方式与 chat / vision 相同；两项都没有 Key 时不发请求。ok 为测试到的各项都成功，耗时为合计，
 *       开关 AI_FALLBACK_ENABLED 为 false 时在说明中注明（测试照常进行）。</li>
 * </ul>
 * 时限 15 秒；失败原因只保留上游返回的前 160 个字，并把当前 Key 替换为 ****，不会把密钥带回网页或日志。
 */
@Component
public class ConfigTester {

    private static final Logger log = LoggerFactory.getLogger(ConfigTester.class);

    static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int SUMMARY_MAX = 160;

    private final RuntimeConfigService runtimeConfig;
    private final VisionProperties visionProperties;
    private final ChatProperties chatProperties;
    private final AsrClient asrClient;
    private final SmsClient smsClient;
    private final SmsService smsService;
    private final OkHttpClient http;

    public ConfigTester(RuntimeConfigService runtimeConfig, VisionProperties visionProperties,
                        ChatProperties chatProperties, AsrClient asrClient, SmsClient smsClient, SmsService smsService,
                        OkHttpClient okHttpClient) {
        this.runtimeConfig = runtimeConfig;
        this.visionProperties = visionProperties;
        this.chatProperties = chatProperties;
        this.asrClient = asrClient;
        this.smsClient = smsClient;
        this.smsService = smsService;
        this.http = okHttpClient.newBuilder().callTimeout(TIMEOUT).readTimeout(TIMEOUT).build();
    }

    public ConfigTestVO test(String group, String phone) {
        return switch (group) {
            case RuntimeConfigItem.Group.VISION -> testCompletion(RuntimeConfigItem.DASHSCOPE_API_KEY,
                    RuntimeConfigItem.VISION_MODEL, visionProperties.getUrl(), Map.of("enable_thinking", false));
            case RuntimeConfigItem.Group.CHAT -> testCompletion(RuntimeConfigItem.DEEPSEEK_API_KEY,
                    RuntimeConfigItem.CHAT_MODEL, chatProperties.getUrl(), Map.of());
            case RuntimeConfigItem.Group.ASR -> testAsr();
            case RuntimeConfigItem.Group.SMS -> testSms(phone);
            case RuntimeConfigItem.Group.FALLBACK -> testFallback();
            default -> throw BizException.param("未知的配置分组：" + group);
        };
    }

    /** 兼容 Chat Completions 接口的最小请求：一句「你好」，非流式 */
    ConfigTestVO testCompletion(RuntimeConfigItem keyItem, RuntimeConfigItem modelItem, String url,
                                Map<String, Object> extra) {
        String key = runtimeConfig.get(keyItem);
        if (key.isBlank()) {
            return new ConfigTestVO(false, "未配置 " + keyItem.key() + "（当前使用 Mock），没有发出请求", 0);
        }
        String model = runtimeConfig.get(modelItem);
        // 流式请求，收到第一个 data 行即说明密钥与模型可用；不等完整回答（深度思考模型非流式时可能超过 15 秒才返回）
        String body = OpenAiCompatible.buildRequest(model, List.of(ChatMessage.user("你好")), true, null, 16, extra);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + key)
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(body, OpenAiCompatible.JSON))
                .build();
        long started = System.nanoTime();
        try (Response response = http.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String text = responseBody == null ? "" : responseBody.string();
                return new ConfigTestVO(false, "HTTP " + response.code() + "：" + summary(text, key), elapsedMillis(started));
            }
            String first = firstDataLine(responseBody);
            long elapsed = elapsedMillis(started);
            if (first == null) {
                return new ConfigTestVO(false, "上游没有返回内容", elapsed);
            }
            if (first.contains("\"error\"")) {
                return new ConfigTestVO(false, "上游返回错误：" + summary(first, key), elapsed);
            }
            return new ConfigTestVO(true, "连接正常（模型 " + model + "）", elapsed);
        } catch (InterruptedIOException e) {
            return new ConfigTestVO(false, TIMEOUT.toSeconds() + " 秒内没有响应", elapsedMillis(started));
        } catch (IOException e) {
            return new ConfigTestVO(false, "连接失败：" + summary(e.getMessage(), key), elapsedMillis(started));
        }
    }

    /** 自动备用：只测试已配置 Key 的备用模型，结果合并为一条 */
    ConfigTestVO testFallback() {
        List<String> parts = new ArrayList<>();
        boolean ok = true;
        long latency = 0;
        int tested = 0;
        if (!runtimeConfig.get(RuntimeConfigItem.DASHSCOPE_API_KEY).isBlank()) {
            ConfigTestVO chat = testCompletion(RuntimeConfigItem.DASHSCOPE_API_KEY, RuntimeConfigItem.CHAT_FALLBACK_MODEL,
                    visionProperties.getUrl(), Map.of("enable_thinking", true));
            parts.add("问答备用：" + chat.message());
            ok &= chat.ok();
            latency += chat.latencyMs();
            tested++;
        } else {
            parts.add("问答备用：未配置 DASHSCOPE_API_KEY，不会启用");
        }
        if (!runtimeConfig.get(RuntimeConfigItem.DEEPSEEK_API_KEY).isBlank()) {
            ConfigTestVO vision = testCompletion(RuntimeConfigItem.DEEPSEEK_API_KEY, RuntimeConfigItem.VISION_FALLBACK_MODEL,
                    chatProperties.getUrl(), Map.of("reasoning_effort", DeepSeekVisionClient.REASONING_EFFORT));
            parts.add("识图备用：" + vision.message());
            ok &= vision.ok();
            latency += vision.latencyMs();
            tested++;
        } else {
            parts.add("识图备用：未配置 DEEPSEEK_API_KEY，不会启用");
        }
        if (!runtimeConfig.fallbackEnabled()) {
            parts.add("（AI_FALLBACK_ENABLED 当前为 false，自动备用未启用）");
        }
        String message = String.join("；", parts);
        if (tested == 0) {
            return new ConfigTestVO(false, message + "；没有发出请求", 0);
        }
        return new ConfigTestVO(ok, message, latency);
    }

    ConfigTestVO testAsr() {
        String key = runtimeConfig.get(RuntimeConfigItem.DASHSCOPE_API_KEY);
        if (key.isBlank()) {
            return new ConfigTestVO(false, "未配置 DASHSCOPE_API_KEY（当前使用 Mock），没有发出请求", 0);
        }
        AsrClient client = asrClient instanceof RoutingAsrClient routing ? routing.current() : asrClient;
        String model = runtimeConfig.get(RuntimeConfigItem.ASR_MODEL);
        long started = System.nanoTime();
        try {
            String text = client.transcribe(WavAudio.silence(16000, 500), TIMEOUT);
            long elapsed = elapsedMillis(started);
            return new ConfigTestVO(true, "连接正常（模型 " + model + "，静音测试音频"
                    + (text == null || text.isBlank() ? "，识别结果为空）" : "）"), elapsed);
        } catch (AsrException e) {
            return new ConfigTestVO(false, summary(e.getMessage(), key), elapsedMillis(started));
        }
    }

    ConfigTestVO testSms(String phone) {
        if (!Rules.isValidPhone(phone)) {
            throw BizException.param(Rules.PHONE_MESSAGE);
        }
        if (smsClient instanceof RoutingSmsClient routing && routing.isLogOnly()) {
            return new ConfigTestVO(false, "阿里云短信未配置齐全（AccessKey ID / Secret / 签名）或 SMS_PROVIDER=log："
                    + "当前验证码只打印到服务器日志，没有发送真实短信", 0);
        }
        long started = System.nanoTime();
        try {
            smsService.send(phone, SmsScene.LOGIN);
            return new ConfigTestVO(true, "测试验证码已发送到尾号 " + phone.substring(phone.length() - 4)
                    + " 的手机（登录验证码，5 分钟内有效）", elapsedMillis(started));
        } catch (BizException e) {
            log.info("短信配置测试失败：{}", e.getMessage());
            return new ConfigTestVO(false, e.getMessage(), elapsedMillis(started));
        }
    }

    /** 截短，并把密钥替换为 ****（上游错误信息一般不含 Key，这里兜底） */
    static String summary(String text, String secret) {
        if (text == null || text.isBlank()) {
            return "没有返回内容";
        }
        String s = text.replaceAll("\\s+", " ").trim();
        if (secret != null && secret.length() >= 4) {
            s = s.replace(secret, "****");
        }
        return s.length() > SUMMARY_MAX ? s.substring(0, SUMMARY_MAX) + "…" : s;
    }

    /** 流式响应的第一个 data 行（去掉「data:」前缀）；跳过空行与注释行，流结束仍没有时返回 null */
    static String firstDataLine(ResponseBody body) throws IOException {
        if (body == null) {
            return null;
        }
        BufferedSource source = body.source();
        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (line.startsWith("data:")) {
                return line.substring(5).trim();
            }
            // 有的平台出错时不按 SSE 格式，直接返回一段 JSON
            if (line.startsWith("{")) {
                return line;
            }
        }
        return null;
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
