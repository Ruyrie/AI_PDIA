package com.heban.pestai.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import com.heban.pestai.server.admin.vo.ConfigItemVO;
import com.heban.pestai.server.admin.vo.ConfigTestVO;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.config.ConfigCipher;
import com.heban.pestai.server.config.RuntimeConfigItem;
import com.heban.pestai.server.config.RuntimeConfigService;
import com.heban.pestai.server.entity.SystemConfig;
import com.heban.pestai.server.mapper.SystemConfigMapper;

/** 系统配置：掩码、来源、密钥类需要管理员密码、恢复、日志中不出现密钥明文。 */
class AdminConfigServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final Map<String, SystemConfig> table = new LinkedHashMap<>();
    private RuntimeConfigService runtimeConfig;
    private AdminAuthService authService;
    private AdminLogService logService;
    private ConfigTester tester;
    private AdminConfigService service;

    @BeforeEach
    void setUp() {
        SystemConfigMapper mapper = mock(SystemConfigMapper.class);
        when(mapper.selectAll()).thenAnswer(inv -> List.copyOf(table.values()));
        when(mapper.upsert(any())).thenAnswer(inv -> {
            SystemConfig row = inv.getArgument(0);
            table.put(row.getConfigKey(), row);
            return 1;
        });
        when(mapper.deleteByKey(anyString())).thenAnswer(inv -> table.remove((String) inv.getArgument(0)) == null ? 0 : 1);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("vision.api-key", "sk-env-abcdefghijklmn1234")
                .withProperty("DASHSCOPE_API_KEY", "sk-env-abcdefghijklmn1234")
                .withProperty("chat.api-key", "")
                .withProperty("vision.model", "qwen3.8-max");
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 10, 14, 9, 0).atZone(ZONE).toInstant(), ZONE);
        runtimeConfig = new RuntimeConfigService(mapper, environment, new ConfigCipher("k", "j"), clock);
        authService = mock(AdminAuthService.class);
        logService = mock(AdminLogService.class);
        tester = mock(ConfigTester.class);
        service = new AdminConfigService(runtimeConfig, authService, logService, tester);
        AdminContext.set(new AdminContext.Principal(1L, "admin", "127.0.0.1"));
    }

    @AfterEach
    void tearDown() {
        AdminContext.clear();
    }

    private ConfigItemVO item(List<ConfigItemVO> list, String key) {
        return list.stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void listMasksSecretsAndReportsSources() {
        List<ConfigItemVO> list = service.list();
        assertThat(list).hasSize(RuntimeConfigItem.values().length);
        ConfigItemVO dashscope = item(list, "DASHSCOPE_API_KEY");
        assertThat(dashscope.secret()).isTrue();
        assertThat(dashscope.value()).isEqualTo("sk-****1234");
        assertThat(dashscope.source()).isEqualTo("ENV");
        assertThat(dashscope.group()).isEqualTo("vision");
        ConfigItemVO deepseek = item(list, "DEEPSEEK_API_KEY");
        assertThat(deepseek.value()).isEmpty();
        assertThat(deepseek.source()).isEqualTo("NONE");
        ConfigItemVO model = item(list, "VISION_MODEL");
        assertThat(model.value()).isEqualTo("qwen3.8-max");
        assertThat(model.source()).isEqualTo("DEFAULT");
        assertThat(item(list, "ASR_MODEL").group()).isEqualTo("asr");

        assertThat(AdminConfigService.mask("")).isEmpty();
        assertThat(AdminConfigService.mask("short-key")).isEqualTo("****");
        assertThat(AdminConfigService.mask("LTAI5tAbCdEfGhIj")).isEqualTo("LTA****GhIj");
    }

    @Test
    void secretUpdateRequiresAdminPasswordAndIsNeverLoggedInPlainText() {
        doThrow(BizException.param("管理员密码不正确")).when(authService).verifyCurrentPassword("bad");
        assertThatThrownBy(() -> service.update("DEEPSEEK_API_KEY", "sk-new-0123456789abcd", "bad"))
                .hasMessage("管理员密码不正确");
        assertThat(table).isEmpty();

        ConfigItemVO updated = service.update("DEEPSEEK_API_KEY", "  sk-new-0123456789abcd  ", "right");
        verify(authService).verifyCurrentPassword("right");
        assertThat(updated.source()).isEqualTo("ADMIN");
        assertThat(updated.value()).isEqualTo("sk-****abcd");
        assertThat(updated.updatedBy()).isEqualTo("admin");
        assertThat(table.get("DEEPSEEK_API_KEY").getConfigValue()).doesNotContain("sk-new");
        assertThat(runtimeConfig.get(RuntimeConfigItem.DEEPSEEK_API_KEY)).isEqualTo("sk-new-0123456789abcd");

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(logService).log(eq(AdminActions.CONFIG_UPDATE), eq("配置 DEEPSEEK_API_KEY"), detail.capture());
        assertThat(detail.getValue()).doesNotContain("sk-new");

        ConfigItemVO reset = service.reset("DEEPSEEK_API_KEY", "right");
        assertThat(reset.source()).isEqualTo("NONE");
        assertThat(runtimeConfig.get(RuntimeConfigItem.DEEPSEEK_API_KEY)).isEmpty();
        verify(logService).log(eq(AdminActions.CONFIG_RESET), eq("配置 DEEPSEEK_API_KEY"), eq("恢复为 未配置"));
    }

    @Test
    void nonSecretUpdateNeedsNoPasswordAndValidatesValue() {
        ConfigItemVO updated = service.update("VISION_MODEL", "qwen-vl-max", null);
        assertThat(updated.value()).isEqualTo("qwen-vl-max");
        verify(authService, never()).verifyCurrentPassword(any());
        verify(logService).log(AdminActions.CONFIG_UPDATE, "配置 VISION_MODEL", "新值：qwen-vl-max");

        assertThatThrownBy(() -> service.update("VISION_MODEL", "   ", null)).hasMessage("配置值不能为空");
        assertThatThrownBy(() -> service.update("VISION_MODEL", "a\nb", null)).hasMessage("配置值不能包含换行等控制字符");
        assertThatThrownBy(() -> service.update("NOT_A_KEY", "x", null)).hasMessage("未知的配置项：NOT_A_KEY");

        assertThat(service.reset("VISION_MODEL", null).source()).isEqualTo("DEFAULT");
    }

    @Test
    void fallbackItemsAreListedAndTheSwitchOnlyAcceptsTrueOrFalse() {
        List<ConfigItemVO> list = service.list();
        assertThat(list).extracting(ConfigItemVO::key).endsWith("AI_FALLBACK_ENABLED", "CHAT_FALLBACK_MODEL",
                "VISION_FALLBACK_MODEL");
        ConfigItemVO enabled = item(list, "AI_FALLBACK_ENABLED");
        assertThat(enabled.group()).isEqualTo("fallback");
        assertThat(enabled.secret()).isFalse();
        assertThat(enabled.value()).isEqualTo("true");
        assertThat(enabled.source()).isEqualTo("DEFAULT");
        assertThat(item(list, "CHAT_FALLBACK_MODEL").value()).isEqualTo("qwen3.8-max");
        assertThat(item(list, "VISION_FALLBACK_MODEL").value()).isEqualTo("deepseek-flash");
        assertThat(runtimeConfig.fallbackEnabled()).isTrue();

        assertThatThrownBy(() -> service.update("AI_FALLBACK_ENABLED", "yes", null))
                .hasMessage("AI_FALLBACK_ENABLED 只能是 true 或 false");
        assertThat(table).isEmpty();
        // 不区分大小写，保存为小写；非密钥类不需要管理员密码
        ConfigItemVO off = service.update("AI_FALLBACK_ENABLED", " FALSE ", null);
        assertThat(off.value()).isEqualTo("false");
        assertThat(runtimeConfig.fallbackEnabled()).isFalse();
        verify(authService, never()).verifyCurrentPassword(any());
        verify(logService).log(AdminActions.CONFIG_UPDATE, "配置 AI_FALLBACK_ENABLED", "新值：false");

        assertThat(service.update("CHAT_FALLBACK_MODEL", "qwen-plus", null).value()).isEqualTo("qwen-plus");
        assertThat(runtimeConfig.get(RuntimeConfigItem.CHAT_FALLBACK_MODEL)).isEqualTo("qwen-plus");
        assertThat(service.reset("AI_FALLBACK_ENABLED", null).value()).isEqualTo("true");
        assertThat(runtimeConfig.fallbackEnabled()).isTrue();
    }

    @Test
    void fallbackGroupCanBeTested() {
        when(tester.test("fallback", null)).thenReturn(new ConfigTestVO(true, "问答备用：连接正常", 80));
        assertThat(service.test("fallback", null).ok()).isTrue();
        verify(logService).log(AdminActions.CONFIG_TEST, "配置分组 fallback", "成功，耗时 80 ms：问答备用：连接正常");
    }

    @Test
    void testDelegatesAndLogsResult() {
        when(tester.test("chat", null)).thenReturn(new ConfigTestVO(false, "HTTP 401：invalid key", 120));
        assertThat(service.test("chat", null).ok()).isFalse();
        verify(logService).log(AdminActions.CONFIG_TEST, "配置分组 chat", "失败，耗时 120 ms：HTTP 401：invalid key");
        assertThatThrownBy(() -> service.test("other", null)).hasMessage("未知的配置分组：other");
        doAnswer(inv -> null).when(logService).log(anyString(), anyString(), anyString());
    }

    @Test
    void summaryScrubsSecretAndTruncates() {
        assertThat(ConfigTester.summary("bad key sk-abcdef123 rejected", "sk-abcdef123")).isEqualTo("bad key **** rejected");
        assertThat(ConfigTester.summary("x".repeat(500), null)).hasSize(161).endsWith("…");
        assertThat(ConfigTester.summary(null, null)).isEqualTo("没有返回内容");
    }

    @Test
    void firstDataLineSkipsCommentsAndBlankLines() throws Exception {
        assertThat(ConfigTester.firstDataLine(okhttp3.ResponseBody.create(
                ": keep-alive\n\ndata: {\"choices\":[]}\n\ndata: [DONE]\n", null))).isEqualTo("{\"choices\":[]}");
        assertThat(ConfigTester.firstDataLine(okhttp3.ResponseBody.create(
                "{\"error\":{\"message\":\"bad\"}}\n", null))).contains("\"error\"");
        assertThat(ConfigTester.firstDataLine(okhttp3.ResponseBody.create("\n: ping\n", null))).isNull();
        assertThat(ConfigTester.firstDataLine(null)).isNull();
    }
}
