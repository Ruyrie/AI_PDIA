package com.heban.pestai.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.config.RuntimeConfigService.Source;
import com.heban.pestai.server.entity.SystemConfig;
import com.heban.pestai.server.mapper.SystemConfigMapper;

/** 运行时配置：生效顺序、来源判断、密钥加密保存、解密失败与读取失败的降级、短信配置快照。 */
class RuntimeConfigServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private SystemConfigMapper mapper;
    private MockEnvironment environment;
    private ConfigCipher cipher;
    private final AtomicLong millis = new AtomicLong(Instant.parse("2026-10-14T01:00:00Z").toEpochMilli());
    private final Clock clock = new Clock() {
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
    };
    private final List<SystemConfig> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mapper = mock(SystemConfigMapper.class);
        when(mapper.selectAll()).thenAnswer(inv -> List.copyOf(rows));
        environment = new MockEnvironment();
        cipher = new ConfigCipher("config-secret-for-tests", "jwt-secret");
    }

    private RuntimeConfigService service() {
        return new RuntimeConfigService(mapper, environment, cipher, clock);
    }

    @Test
    void precedenceIsAdminThenEnvThenDefault() {
        // application.yml 中 vision.model 绑定 ${VISION_MODEL:qwen3.8-max}：这里直接给出解析后的属性值
        environment.setProperty("vision.model", "qwen-vl-env");
        environment.setProperty("VISION_MODEL", "qwen-vl-env");
        environment.setProperty("chat.model", "deepseek-flash");
        environment.setProperty("vision.api-key", "");
        RuntimeConfigService service = service();

        assertThat(service.get(RuntimeConfigItem.VISION_MODEL)).isEqualTo("qwen-vl-env");
        assertThat(service.entry(RuntimeConfigItem.VISION_MODEL).source()).isEqualTo(Source.ENV);
        assertThat(service.entry(RuntimeConfigItem.CHAT_MODEL).source()).isEqualTo(Source.DEFAULT);
        // 属性不存在时取枚举中的默认值
        assertThat(service.get(RuntimeConfigItem.ASR_MODEL)).isEqualTo("qwen3-asr-flash");
        assertThat(service.entry(RuntimeConfigItem.ASR_MODEL).source()).isEqualTo(Source.DEFAULT);
        assertThat(service.get(RuntimeConfigItem.DASHSCOPE_API_KEY)).isEmpty();
        assertThat(service.entry(RuntimeConfigItem.DASHSCOPE_API_KEY).source()).isEqualTo(Source.NONE);

        rows.add(row("VISION_MODEL", "qwen-vl-admin", false));
        service.reload();
        assertThat(service.get(RuntimeConfigItem.VISION_MODEL)).isEqualTo("qwen-vl-admin");
        RuntimeConfigService.Entry entry = service.entry(RuntimeConfigItem.VISION_MODEL);
        assertThat(entry.source()).isEqualTo(Source.ADMIN);
        assertThat(entry.updatedBy()).isEqualTo("admin");
        assertThat(service.baseValue(RuntimeConfigItem.VISION_MODEL)).isEqualTo("qwen-vl-env");

        rows.clear();
        service.reload();
        assertThat(service.get(RuntimeConfigItem.VISION_MODEL)).isEqualTo("qwen-vl-env");
    }

    @Test
    void fallbackSwitchDefaultsToOnAndOnlyExplicitOffValuesTurnItOff() {
        RuntimeConfigService service = service();
        // 属性不存在：取默认值 true
        assertThat(service.fallbackEnabled()).isTrue();
        assertThat(service.get(RuntimeConfigItem.CHAT_FALLBACK_MODEL)).isEqualTo("qwen3.8-max");
        assertThat(service.get(RuntimeConfigItem.VISION_FALLBACK_MODEL)).isEqualTo("deepseek-flash");
        assertThat(RuntimeConfigItem.AI_FALLBACK_ENABLED.group()).isEqualTo(RuntimeConfigItem.Group.FALLBACK);
        assertThat(RuntimeConfigItem.Group.isValid("fallback")).isTrue();
        assertThat(RuntimeConfigItem.AI_FALLBACK_ENABLED.isSwitch()).isTrue();
        assertThat(RuntimeConfigItem.CHAT_FALLBACK_MODEL.isSwitch()).isFalse();

        for (String off : List.of("false", "FALSE", "0", "no", "Off")) {
            environment.setProperty("ai-fallback.enabled", off);
            assertThat(service().fallbackEnabled()).as(off).isFalse();
        }
        for (String on : List.of("true", "TRUE", "1", "yes", "")) {
            environment.setProperty("ai-fallback.enabled", on);
            assertThat(service().fallbackEnabled()).as(on).isTrue();
        }

        // 后台设置优先
        environment.setProperty("ai-fallback.enabled", "true");
        rows.add(row("AI_FALLBACK_ENABLED", "false", false));
        assertThat(service().fallbackEnabled()).isFalse();
    }

    @Test
    void envOverriddenToBlankByPropertyIsNone() {
        // 测试中常用 vision.api-key= 覆盖 .env 里的真实 Key：以属性值为准
        environment.setProperty("DASHSCOPE_API_KEY", "sk-from-dotenv");
        environment.setProperty("vision.api-key", "");
        assertThat(service().entry(RuntimeConfigItem.DASHSCOPE_API_KEY).source()).isEqualTo(Source.NONE);
    }

    @Test
    void secretsAreEncryptedAtRestAndDecryptedInMemory() throws Exception {
        RuntimeConfigService service = service();
        service.set(RuntimeConfigItem.DEEPSEEK_API_KEY, "sk-live-1234567890abcdef", "admin");

        ArgumentCaptor<SystemConfig> saved = ArgumentCaptor.forClass(SystemConfig.class);
        verify(mapper).upsert(saved.capture());
        SystemConfig stored = saved.getValue();
        assertThat(stored.getSecret()).isTrue();
        assertThat(stored.getConfigValue()).startsWith(ConfigCipher.PREFIX).doesNotContain("sk-live");
        assertThat(cipher.decrypt(stored.getConfigValue())).isEqualTo("sk-live-1234567890abcdef");
        // 同一明文两次加密结果不同（随机 IV）
        assertThat(cipher.encrypt("x")).isNotEqualTo(cipher.encrypt("x"));

        rows.add(stored);
        service.reload();
        assertThat(service.get(RuntimeConfigItem.DEEPSEEK_API_KEY)).isEqualTo("sk-live-1234567890abcdef");

        // 非密钥类明文保存
        service.set(RuntimeConfigItem.CHAT_MODEL, "deepseek-v4-pro", "admin");
        verify(mapper, times(2)).upsert(saved.capture());
        assertThat(saved.getValue().getConfigValue()).isEqualTo("deepseek-v4-pro");
        assertThat(saved.getValue().getSecret()).isFalse();
    }

    @Test
    void undecryptableSecretIsTreatedAsUnset() {
        environment.setProperty("chat.api-key", "sk-env");
        String encryptedWithOtherKey = new ConfigCipher("another-secret", "jwt").encrypt("sk-admin");
        rows.add(row("DEEPSEEK_API_KEY", encryptedWithOtherKey, true));
        rows.add(row("DASHSCOPE_API_KEY", "not-a-ciphertext", true));
        RuntimeConfigService service = service();

        assertThat(service.get(RuntimeConfigItem.DEEPSEEK_API_KEY)).isEqualTo("sk-env");
        assertThat(service.entry(RuntimeConfigItem.DEEPSEEK_API_KEY).source()).isEqualTo(Source.ENV);
        assertThat(service.get(RuntimeConfigItem.DASHSCOPE_API_KEY)).isEmpty();
    }

    @Test
    void cipherKeyFallsBackToJwtSecretAndTamperingIsDetected() {
        ConfigCipher fromJwt = new ConfigCipher("", "jwt-secret-a");
        assertThat(fromJwt.isDerivedFromJwtSecret()).isTrue();
        String encrypted = fromJwt.encrypt("value");
        assertThatThrownBy(() -> new ConfigCipher(null, "jwt-secret-b").decrypt(encrypted))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> new ConfigCipher("x", "jwt-secret-a").decrypt(encrypted))
                .isInstanceOf(GeneralSecurityException.class);
        char[] chars = encrypted.toCharArray();
        chars[chars.length - 3] = chars[chars.length - 3] == 'A' ? 'B' : 'A';
        assertThatThrownBy(() -> fromJwt.decrypt(new String(chars))).isInstanceOf(GeneralSecurityException.class);
        assertThat(new ConfigCipher(" ", "jwt-secret-a").isDerivedFromJwtSecret()).isTrue();
    }

    @Test
    void loadFailureFallsBackToEnvAndRetriesAfterOneMinute() {
        environment.setProperty("vision.model", "qwen3.8-max");
        when(mapper.selectAll()).thenThrow(new RuntimeException("Table 'pestai.t_system_config' doesn't exist"))
                .thenAnswer(inv -> List.of(row("VISION_MODEL", "qwen-admin", false)));
        RuntimeConfigService service = service();

        assertThat(service.get(RuntimeConfigItem.VISION_MODEL)).isEqualTo("qwen3.8-max");
        assertThat(service.get(RuntimeConfigItem.VISION_MODEL)).isEqualTo("qwen3.8-max");
        verify(mapper, times(1)).selectAll();

        millis.addAndGet(RuntimeConfigService.RETRY_MILLIS + 1);
        assertThat(service.get(RuntimeConfigItem.VISION_MODEL)).isEqualTo("qwen-admin");
        verify(mapper, times(2)).selectAll();
        // 读取成功后不再查询，直到下一次修改
        service.get(RuntimeConfigItem.VISION_MODEL);
        verify(mapper, times(2)).selectAll();
    }

    @Test
    void smsSnapshotUsesEffectiveValuesAndKeepsEndpoint() {
        environment.setProperty("sms.aliyun.access-key-id", "LTAI-env");
        environment.setProperty("sms.aliyun.template-reset", "");
        rows.add(row("ALIYUN_SMS_ACCESS_KEY_SECRET", cipher.encrypt("secret-admin"), true));
        rows.add(row("ALIYUN_SMS_SIGN_NAME", "恒创联众", false));
        SmsProperties.Aliyun base = new SmsProperties.Aliyun();
        base.setEndpoint("https://example.test/");
        base.setValidMinutes(7);

        SmsProperties.Aliyun snapshot = service().smsAliyun(base);
        assertThat(snapshot.getAccessKeyId()).isEqualTo("LTAI-env");
        assertThat(snapshot.getAccessKeySecret()).isEqualTo("secret-admin");
        assertThat(snapshot.getSignName()).isEqualTo("恒创联众");
        assertThat(snapshot.getEndpoint()).isEqualTo("https://example.test/");
        assertThat(snapshot.getValidMinutes()).isEqualTo(7);
        assertThat(snapshot.isComplete()).isTrue();
        // 模板配置为空串时与原来一样用默认模板兜底
        assertThat(snapshot.templateFor(SmsScene.RESET)).isEqualTo("100001");
        assertThat(snapshot.templateFor(SmsScene.REBIND)).isEqualTo("100004");
    }

    @Test
    void unknownKeysAreIgnored() {
        rows.add(row("SOMETHING_ELSE", "x", false));
        assertThat(service().entries()).hasSize(RuntimeConfigItem.values().length);
        assertThat(RuntimeConfigItem.of("SOMETHING_ELSE")).isEmpty();
        assertThat(RuntimeConfigItem.of("ASR_MODEL")).contains(RuntimeConfigItem.ASR_MODEL);
    }

    @Test
    void setFailurePropagates() {
        when(mapper.upsert(any())).thenThrow(new RuntimeException("db down"));
        assertThatThrownBy(() -> service().set(RuntimeConfigItem.CHAT_MODEL, "m", "admin"))
                .hasMessageContaining("db down");
    }

    private static SystemConfig row(String key, String value, boolean secret) {
        SystemConfig row = new SystemConfig();
        row.setConfigKey(key);
        row.setConfigValue(value);
        row.setSecret(secret);
        row.setUpdatedBy("admin");
        row.setUpdatedAt(LocalDateTime.of(2026, 10, 14, 9, 0));
        return row;
    }
}
