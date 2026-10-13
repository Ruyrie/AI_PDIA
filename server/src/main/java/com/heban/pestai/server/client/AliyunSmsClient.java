package com.heban.pestai.server.client;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.config.SmsProperties;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 阿里云号码认证服务 · 短信认证（Dypnsapi 2017-05-25 SendSmsVerifyCode），使用控制台赠送的签名与模板。
 * 验证码由 SmsService 生成并校验，这里以 TemplateParam {"code":"123456","min":"5"} 原样下发。
 * 为避免引入阿里云 SDK（其依赖的 OkHttp 3.x 与本工程的 OkHttp 4.12.0 冲突），直接用 OkHttp 按 RPC 签名规范（HMAC-SHA1）调用。
 * AccessKey 来自配置，只在本类内部使用，不写日志。
 */
public class AliyunSmsClient implements SmsClient {

    private static final Logger log = LoggerFactory.getLogger(AliyunSmsClient.class);
    private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final SmsProperties.Aliyun config;
    private final OkHttpClient client;
    private final Clock clock;

    public AliyunSmsClient(SmsProperties.Aliyun config, OkHttpClient baseClient, Clock clock) {
        this.config = config;
        this.client = baseClient.newBuilder()
                .callTimeout(Duration.ofSeconds(10))
                .build();
        this.clock = clock;
    }

    @Override
    public void sendCode(String phone, SmsScene scene, String code) throws SmsSendException {
        String templateCode = config.templateFor(scene);
        String bizId = send(phone, templateCode, templateParam(code));
        log.info("阿里云短信已发送：场景 {}，模板 {}，手机号尾号 {}，回执 {}", scene, templateCode, tail(phone), bizId);
    }

    @Override
    public String name() {
        return "AliyunSmsClient（阿里云号码认证服务 · 短信认证）";
    }

    String templateParam(String code) {
        Map<String, String> param = new LinkedHashMap<>();
        param.put("code", code);
        param.put("min", String.valueOf(config.getValidMinutes()));
        try {
            return OpenAiCompatible.MAPPER.writeValueAsString(param);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 调用 SendSmsVerifyCode，成功返回 BizId */
    String send(String phone, String templateCode, String templateParam) throws SmsSendException {
        Request request = new Request.Builder().url(buildUrl(phone, templateCode, templateParam)).get().build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            JsonNode json = OpenAiCompatible.MAPPER.readTree(text.isEmpty() ? "{}" : text);
            String code = json.path("Code").asText("");
            if (!response.isSuccessful() || !"OK".equals(code)) {
                throw new SmsSendException(kindOf(code), "阿里云短信发送失败：HTTP " + response.code()
                        + "，Code=" + code + "，Message=" + json.path("Message").asText(""));
            }
            return json.path("Model").path("BizId").asText("");
        } catch (IOException e) {
            throw new SmsSendException("阿里云短信接口连接失败：" + e.getMessage(), e);
        }
    }

    /** 频控类错误转「操作太频繁」，手机号非法转参数错误，其余按服务器错误处理 */
    static SmsSendException.Kind kindOf(String code) {
        if (code == null) {
            return SmsSendException.Kind.OTHER;
        }
        if (code.contains("FREQUENCY") || code.contains("LIMIT_CONTROL") || code.contains("Throttling")) {
            return SmsSendException.Kind.FREQUENCY;
        }
        // 文档写作 MOBILE_NUMBER_ILLEGAL，实际接口返回 ILLEGAL_MOBILE，两种都认
        if (code.contains("MOBILE") && code.contains("ILLEGAL")) {
            return SmsSendException.Kind.INVALID_PHONE;
        }
        return SmsSendException.Kind.OTHER;
    }

    String buildUrl(String phone, String templateCode, String templateParam) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("AccessKeyId", config.getAccessKeyId());
        params.put("Action", "SendSmsVerifyCode");
        params.put("Format", "JSON");
        params.put("PhoneNumber", phone);
        params.put("SignName", config.getSignName());
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", "1.0");
        params.put("TemplateCode", templateCode);
        params.put("TemplateParam", templateParam);
        params.put("ValidTime", String.valueOf(config.getValidMinutes() * 60));
        params.put("Timestamp", ISO_UTC.format(clock.instant().atOffset(ZoneOffset.UTC)));
        params.put("Version", "2017-05-25");
        String signature = AliyunRpcSigner.sign("GET", params, config.getAccessKeySecret());
        String endpoint = config.getEndpoint().endsWith("/") ? config.getEndpoint() : config.getEndpoint() + "/";
        return endpoint + "?Signature=" + AliyunRpcSigner.percentEncode(signature)
                + "&" + AliyunRpcSigner.canonicalQuery(params);
    }

    private static String tail(String phone) {
        return phone == null || phone.length() < 4 ? "" : phone.substring(phone.length() - 4);
    }
}
