package com.heban.pestai.server.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.config.SmsProperties;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

class AliyunRpcSignerTest {

    /** 阿里云短信文档「HTTP 协议及签名」中的示例参数 */
    private static Map<String, String> docExample() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("SignatureMethod", "HMAC-SHA1");
        p.put("SignatureNonce", "45e25e9b-0a6f-4070-8c85-2956eda1b466");
        p.put("AccessKeyId", "testId");
        p.put("SignatureVersion", "1.0");
        p.put("Timestamp", "2017-07-12T02:42:19Z");
        p.put("Format", "XML");
        p.put("Action", "SendSms");
        p.put("Version", "2017-05-25");
        p.put("RegionId", "cn-hangzhou");
        p.put("PhoneNumbers", "15300000001");
        p.put("SignName", "阿里云短信测试专用");
        p.put("TemplateParam", "{\"customer\":\"test\"}");
        p.put("TemplateCode", "SMS_71390007");
        p.put("OutId", "123");
        return p;
    }

    @Test
    void percentEncodeFollowsRfc3986() {
        assertThat(AliyunRpcSigner.percentEncode("a b*c~d/")).isEqualTo("a%20b%2Ac~d%2F");
        assertThat(AliyunRpcSigner.percentEncode("{\"code\":\"1\"}")).isEqualTo("%7B%22code%22%3A%221%22%7D");
    }

    @Test
    void canonicalQueryIsSortedAndEncoded() {
        assertThat(AliyunRpcSigner.canonicalQuery(docExample())).isEqualTo(
                "AccessKeyId=testId&Action=SendSms&Format=XML&OutId=123&PhoneNumbers=15300000001"
                        + "&RegionId=cn-hangzhou"
                        + "&SignName=%E9%98%BF%E9%87%8C%E4%BA%91%E7%9F%AD%E4%BF%A1%E6%B5%8B%E8%AF%95%E4%B8%93%E7%94%A8"
                        + "&SignatureMethod=HMAC-SHA1&SignatureNonce=45e25e9b-0a6f-4070-8c85-2956eda1b466"
                        + "&SignatureVersion=1.0&TemplateCode=SMS_71390007"
                        + "&TemplateParam=%7B%22customer%22%3A%22test%22%7D"
                        + "&Timestamp=2017-07-12T02%3A42%3A19Z&Version=2017-05-25");
    }

    @Test
    void signatureMatchesDocumentedExample() {
        assertThat(AliyunRpcSigner.sign("GET", docExample(), "testSecret")).isEqualTo("zJDF+Lrzhj/ThnlvIToysFRq6t4=");
    }

    @Test
    void sendSmsVerifyCodeUrlCarriesSignatureAndTemplateParam() {
        SmsProperties.Aliyun config = new SmsProperties.Aliyun();
        config.setAccessKeyId("id");
        config.setAccessKeySecret("secret");
        config.setSignName("恒创联众");
        Clock clock = Clock.fixed(Instant.parse("2026-10-13T06:00:00Z"), ZoneOffset.UTC);
        AliyunSmsClient client = new AliyunSmsClient(config, new OkHttpClient(), clock);

        String param = client.templateParam("048215");
        assertThat(param).isEqualTo("{\"code\":\"048215\",\"min\":\"5\"}");
        HttpUrl url = HttpUrl.get(client.buildUrl("13800005678", "100001", param));
        assertThat(url.host()).isEqualTo("dypnsapi.aliyuncs.com");
        assertThat(url.queryParameter("Action")).isEqualTo("SendSmsVerifyCode");
        assertThat(url.queryParameter("PhoneNumber")).isEqualTo("13800005678");
        assertThat(url.queryParameter("SignName")).isEqualTo("恒创联众");
        assertThat(url.queryParameter("TemplateCode")).isEqualTo("100001");
        assertThat(url.queryParameter("TemplateParam")).isEqualTo(param);
        assertThat(url.queryParameter("ValidTime")).isEqualTo("300");
        assertThat(url.queryParameter("Timestamp")).isEqualTo("2026-10-13T06:00:00Z");
        assertThat(url.queryParameter("Signature")).isNotBlank();
    }

    @Test
    void templateChosenByScene() {
        SmsProperties.Aliyun config = new SmsProperties.Aliyun();
        assertThat(config.templateFor(SmsScene.LOGIN)).isEqualTo("100001");
        assertThat(config.templateFor(SmsScene.REGISTER)).isEqualTo("100001");
        assertThat(config.templateFor(SmsScene.RESET)).isEqualTo("100003");
        assertThat(config.templateFor(SmsScene.REBIND)).isEqualTo("100004");
        assertThat(config.templateFor(SmsScene.CHANGE_PHONE)).isEqualTo("100002");
        assertThat(config.templateFor(SmsScene.DEACTIVATE)).isEqualTo("100005");
        // 生成恢复码与注销账号一样，验证码发往当前手机号，共用验证绑定手机号模板
        assertThat(config.templateFor(SmsScene.RECOVERY)).isEqualTo("100005");
        config.setTemplateRebind("");
        assertThat(config.templateFor(SmsScene.REBIND)).isEqualTo("100001");
    }

    @Test
    void rebindAndVerifyTemplatesAreConfigurable() {
        SmsProperties.Aliyun config = new SmsProperties.Aliyun();
        config.setTemplateRebind("200004");
        config.setTemplateVerify("200005");
        assertThat(config.getTemplateRebind()).isEqualTo("200004");
        assertThat(config.templateFor(SmsScene.REBIND)).isEqualTo("200004");
        assertThat(config.templateFor(SmsScene.DEACTIVATE)).isEqualTo("200005");
        assertThat(config.templateFor(SmsScene.RECOVERY)).isEqualTo("200005");

        config.setTemplateVerify(" ");
        assertThat(config.templateFor(SmsScene.RECOVERY)).isEqualTo("100001");
    }

    @Test
    void newSceneTemplatesAreConfigurableAndFallBackToDefault() {
        SmsProperties.Aliyun config = new SmsProperties.Aliyun();
        config.setTemplateChangePhone("200002");
        config.setTemplateVerify("200005");
        assertThat(config.templateFor(SmsScene.CHANGE_PHONE)).isEqualTo("200002");
        assertThat(config.templateFor(SmsScene.DEACTIVATE)).isEqualTo("200005");

        config.setTemplateChangePhone(" ");
        config.setTemplateVerify(null);
        assertThat(config.templateFor(SmsScene.CHANGE_PHONE)).isEqualTo("100001");
        assertThat(config.templateFor(SmsScene.DEACTIVATE)).isEqualTo("100001");
    }

    @Test
    void onlyLoginRegisterResetRebindArePublicScenes() {
        assertThat(SmsScene.LOGIN.isPublic()).isTrue();
        assertThat(SmsScene.REGISTER.isPublic()).isTrue();
        assertThat(SmsScene.RESET.isPublic()).isTrue();
        assertThat(SmsScene.REBIND.isPublic()).isTrue();
        assertThat(SmsScene.CHANGE_PHONE.isPublic()).isFalse();
        assertThat(SmsScene.DEACTIVATE.isPublic()).isFalse();
        assertThat(SmsScene.RECOVERY.isPublic()).isFalse();
        assertThat(SmsScene.values()).extracting(Enum::name).doesNotContain("APPEAL");
    }

    @Test
    void aliyunErrorCodesMapToKinds() {
        assertThat(AliyunSmsClient.kindOf("biz.FREQUENCY")).isEqualTo(SmsClient.SmsSendException.Kind.FREQUENCY);
        assertThat(AliyunSmsClient.kindOf("FREQUENCY_FAIL")).isEqualTo(SmsClient.SmsSendException.Kind.FREQUENCY);
        assertThat(AliyunSmsClient.kindOf("BUSINESS_LIMIT_CONTROL")).isEqualTo(SmsClient.SmsSendException.Kind.FREQUENCY);
        assertThat(AliyunSmsClient.kindOf("MOBILE_NUMBER_ILLEGAL")).isEqualTo(SmsClient.SmsSendException.Kind.INVALID_PHONE);
        assertThat(AliyunSmsClient.kindOf("ILLEGAL_MOBILE")).isEqualTo(SmsClient.SmsSendException.Kind.INVALID_PHONE);
        assertThat(AliyunSmsClient.kindOf("INVALID_PARAMETERS")).isEqualTo(SmsClient.SmsSendException.Kind.OTHER);
    }
}
