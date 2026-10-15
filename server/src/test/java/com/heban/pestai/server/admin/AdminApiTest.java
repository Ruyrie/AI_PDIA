package com.heban.pestai.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import com.heban.pestai.server.admin.entity.Admin;
import com.heban.pestai.server.admin.mapper.AdminLogMapper;
import com.heban.pestai.server.admin.mapper.AdminMapper;
import com.heban.pestai.server.admin.mapper.AdminStatsMapper;
import com.heban.pestai.server.admin.mapper.AdminUserMapper;
import com.heban.pestai.server.client.MockAsrClient;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;
import com.heban.pestai.server.mapper.RecognitionMapper;
import com.heban.pestai.server.mapper.SystemConfigMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.security.JwtTokenProvider;
import com.heban.pestai.server.service.WavAudio;

/**
 * 启动完整的 Spring 容器（不连接数据库：Mapper 全部 Mock，事务管理器也 Mock），验证后台接口：
 * 不加 /pestai/v1 前缀、不认 App 令牌、会话登录、CSRF、必须先改初始密码、登录锁定、用户操作与配置接口；
 * 后台网页与 WebJars 静态资源（不带版本号的地址）；App 语音识别接口（未配置 Key 时为 Mock）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/pestai_unit_test_unreachable",
        "spring.datasource.username=nobody",
        "spring.datasource.password=",
        "vision.api-key=",
        "chat.api-key=",
        "sms.provider=log",
        "pestai.file-root=target/test-files",
        "pestai.public-base-url=http://10.0.2.2:8080",
        "pestai.jwt.secret=integration-test-secret-0123456789abcdef",
        "pestai.admin.initial-password="
})
@AutoConfigureMockMvc
class AdminApiTest {

    private static final String PASSWORD = "Initial-Pass-2026";
    private static final AtomicInteger IP_SEQ = new AtomicInteger(1);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider tokens;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AdminMapper adminMapper;
    @MockBean
    private AdminLogMapper adminLogMapper;
    @MockBean
    private AdminStatsMapper adminStatsMapper;
    @MockBean
    private AdminUserMapper adminUserMapper;
    @MockBean
    private SystemConfigMapper systemConfigMapper;
    @MockBean
    private UserMapper userMapper;
    @MockBean
    private RecognitionMapper recognitionMapper;
    @MockBean
    private AiSessionMapper aiSessionMapper;
    @MockBean
    private AiMessageMapper aiMessageMapper;
    @MockBean
    private PlatformTransactionManager transactionManager;

    private Admin admin;

    @BeforeEach
    void setUp() {
        admin = new Admin();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        admin.setMustChangePassword(false);
        admin.setFailedAttempts(0);
        when(adminMapper.selectByUsername("admin")).thenReturn(admin);
        when(adminMapper.selectById(1L)).thenReturn(admin);
        when(adminMapper.updatePassword(eq(1L), anyString(), any())).thenAnswer(inv -> {
            admin.setPasswordHash(inv.getArgument(1));
            admin.setMustChangePassword(false);
            return 1;
        });
    }

    /** 每次用不同的客户端 IP（MockMvc 的直连地址是 127.0.0.1，按 Nginx 转发处理，取 X-Real-IP） */
    private static String nextIp() {
        return "198.51.100." + IP_SEQ.getAndIncrement();
    }

    private record Login(MockHttpSession session, String csrf) {
    }

    private Login login() throws Exception {
        MvcResult result = mvc.perform(post("/admin/api/auth/login").header("X-Real-IP", nextIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new Login((MockHttpSession) result.getRequest().getSession(false), data.path("csrf_token").asText());
    }

    // ------------------------------------------------------------------ 路由、登录、CSRF

    @Test
    void adminApiIsNotUnderAppPrefixAndIgnoresAppTokens() throws Exception {
        String bearer = "Bearer " + tokens.issue(10001L, 0);
        mvc.perform(get("/admin/api/auth/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.message").value("请先登录"));
        // 后台控制器不挂在 /pestai/v1 下（该前缀下由 JwtInterceptor 先拦截，带有效令牌时为 404）
        User auth = new User();
        auth.setId(10001L);
        auth.setTokenVersion(0);
        auth.setStatus(User.STATUS_NORMAL);
        when(userMapper.selectAuthInfo(10001L)).thenReturn(auth);
        mvc.perform(get("/pestai/v1/admin/api/stats/overview").header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void loginReturnsCsrfAndSessionThenMeAndLogout() throws Exception {
        Login login = login();
        assertThat(login.csrf()).isNotBlank();
        assertThat(login.session().getAttribute(AdminAuthService.ATTR_ADMIN_ID)).isEqualTo(1L);

        mvc.perform(get("/admin/api/auth/me").session(login.session()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.must_change_password").value(false))
                .andExpect(jsonPath("$.data.csrf_token").value(login.csrf()));

        // 非 GET 请求缺少或带错 CSRF 令牌：40301
        mvc.perform(post("/admin/api/auth/logout").session(login.session()))
                .andExpect(jsonPath("$.code").value(40301));
        mvc.perform(post("/admin/api/auth/logout").session(login.session()).header("X-CSRF-Token", "forged"))
                .andExpect(jsonPath("$.code").value(40301));
        mvc.perform(post("/admin/api/auth/logout").session(login.session()).header("X-CSRF-Token", login.csrf()))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(login.session().isInvalid()).isTrue();
        verify(adminLogMapper, org.mockito.Mockito.atLeast(2)).insert(any());
    }

    @Test
    void wrongPasswordIs40102AndFiveFailuresFromOneIpLock() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/admin/api/auth/login").header("X-Real-IP", ip).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"wrong-" + i + "\"}"))
                    .andExpect(jsonPath("$.code").value(40102));
        }
        mvc.perform(post("/admin/api/auth/login").header("X-Real-IP", ip).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong-5\"}"))
                .andExpect(jsonPath("$.code").value(42901))
                .andExpect(jsonPath("$.message").value("登录失败次数过多，请 15 分钟后再试"));
        mvc.perform(post("/admin/api/auth/login").header("X-Real-IP", ip).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(42901));
        mvc.perform(post("/admin/api/auth/login").header("X-Real-IP", nextIp()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"x\"}"))
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void mustChangeInitialPasswordBlocksEverythingElse() throws Exception {
        admin.setMustChangePassword(true);
        Login login = login();
        mvc.perform(get("/admin/api/stats/overview").session(login.session()))
                .andExpect(jsonPath("$.code").value(40302))
                .andExpect(jsonPath("$.message").value("请先修改初始密码"));
        mvc.perform(get("/admin/api/auth/me").session(login.session()))
                .andExpect(jsonPath("$.data.must_change_password").value(true));

        mvc.perform(post("/admin/api/auth/password").session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"old_password\":\"" + PASSWORD + "\",\"new_password\":\"short1\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("新密码至少 10 位，并同时包含字母和数字"));
        mvc.perform(post("/admin/api/auth/password").session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"old_password\":\"" + PASSWORD + "\",\"new_password\":\"Garden2026Secure\"}"))
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/admin/api/stats/overview").session(login.session()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.users.active_7d").value(0))
                .andExpect(jsonPath("$.data.recognitions.success_rate_7d").value(0.0));
    }

    // ------------------------------------------------------------------ 统计、用户、配置、日志

    @Test
    void statsTrendValidatesDays() throws Exception {
        Login login = login();
        mvc.perform(get("/admin/api/stats/trend").param("days", "30").session(login.session()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.dates.length()").value(30))
                .andExpect(jsonPath("$.data.sms_sent.length()").value(30));
        mvc.perform(get("/admin/api/stats/trend").param("days", "8").session(login.session()))
                .andExpect(jsonPath("$.code").value(40001));
        mvc.perform(get("/admin/api/stats/distribution").session(login.session()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.top_results").isArray());
    }

    @Test
    void userOperations() throws Exception {
        Login login = login();
        User user = new User();
        user.setId(10001L);
        user.setPhone("13800005678");
        user.setStatus(User.STATUS_NORMAL);
        when(userMapper.selectById(10001L)).thenReturn(user);

        mvc.perform(get("/admin/api/users").param("status", "FROZEN").session(login.session()))
                .andExpect(jsonPath("$.code").value(40001));
        mvc.perform(get("/admin/api/users").param("size", "101").session(login.session()))
                .andExpect(jsonPath("$.code").value(40001));
        mvc.perform(get("/admin/api/users").param("keyword", "138").session(login.session()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.has_more").value(false));

        mvc.perform(post("/admin/api/users/10001/disable").session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"发布广告\"}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(adminUserMapper).updateStatus(eq(10001L), eq(User.STATUS_DISABLED), eq(true), any());

        // 请求体可省略
        mvc.perform(post("/admin/api/users/10001/disable").session(login.session()).header("X-CSRF-Token", login.csrf()))
                .andExpect(jsonPath("$.code").value(0));

        MvcResult reset = mvc.perform(post("/admin/api/users/10001/reset-password").session(login.session())
                        .header("X-CSRF-Token", login.csrf()))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String temp = objectMapper.readTree(reset.getResponse().getContentAsString()).path("data").path("temp_password").asText();
        assertThat(temp).hasSize(12);
        verify(userMapper).updatePasswordAndBumpVersion(eq(10001L), anyString(), any());

        mvc.perform(post("/admin/api/users/10001/deactivate").session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirm_phone\":\"5678\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("手机号不一致，请输入该用户的完整手机号"));
        verify(userMapper, never()).deleteById(anyLong());

        mvc.perform(put("/admin/api/users/10001/profile").session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"老李\",\"identity\":\"果农\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("种植身份只能是种植户、家庭园艺或农技人员"));

        mvc.perform(delete("/admin/api/recognitions/99").session(login.session()).header("X-CSRF-Token", login.csrf()))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(delete("/admin/api/sessions/99").session(login.session()).header("X-CSRF-Token", login.csrf()))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/admin/api/sessions/99/messages").session(login.session()))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("会话不存在"));
    }

    @Test
    void configListMasksAndSecretUpdateNeedsAdminPassword() throws Exception {
        Login login = login();
        mvc.perform(get("/admin/api/config").session(login.session()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].key").value("DASHSCOPE_API_KEY"))
                .andExpect(jsonPath("$.data[0].secret").value(true))
                .andExpect(jsonPath("$.data[0].source").value("NONE"))
                .andExpect(jsonPath("$.data[?(@.key == 'ASR_MODEL')].value").value("qwen3-asr-flash"))
                .andExpect(jsonPath("$.data[?(@.key == 'ASR_MODEL')].group").value("asr"))
                .andExpect(jsonPath("$.data[?(@.key == 'AI_FALLBACK_ENABLED')].group").value("fallback"))
                .andExpect(jsonPath("$.data[?(@.key == 'VISION_FALLBACK_MODEL')].secret").value(false));

        mvc.perform(put("/admin/api/config/DEEPSEEK_API_KEY").session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"sk-1234567890abcdef\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("管理员密码不正确"));
        verify(systemConfigMapper, never()).upsert(any());

        mvc.perform(put("/admin/api/config/DEEPSEEK_API_KEY").session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"sk-1234567890abcdef\",\"admin_password\":\"" + PASSWORD + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.key").value("DEEPSEEK_API_KEY"));
        verify(systemConfigMapper).upsert(any());

        mvc.perform(post("/admin/api/config/test/vision").session(login.session()).header("X-CSRF-Token", login.csrf()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.latency_ms").value(0));
        mvc.perform(post("/admin/api/config/test/sms").session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"13800005678\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ok").value(false));
        mvc.perform(post("/admin/api/config/test/other").session(login.session()).header("X-CSRF-Token", login.csrf()))
                .andExpect(jsonPath("$.code").value(40001));
        // 自动备用：两个备用模型的 Key 都没有生效时不发请求
        mvc.perform(post("/admin/api/config/test/fallback").session(login.session()).header("X-CSRF-Token", login.csrf()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.latency_ms").value(0));
        mvc.perform(put("/admin/api/config/AI_FALLBACK_ENABLED").session(login.session()).header("X-CSRF-Token", login.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"maybe\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("AI_FALLBACK_ENABLED 只能是 true 或 false"));
        mvc.perform(delete("/admin/api/config/ASR_MODEL").session(login.session()).header("X-CSRF-Token", login.csrf()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.source").value("DEFAULT"));

        mvc.perform(get("/admin/api/logs").param("action", "LOGIN").session(login.session()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray());
    }

    // ------------------------------------------------------------------ 网页与 WebJars

    @Test
    void adminPageRedirectsAndServesIndex() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/"));
        mvc.perform(get("/admin/")).andExpect(status().isOk()).andExpect(forwardedUrl("/admin/index.html"));
        mvc.perform(get("/admin/index.html")).andExpect(status().isOk());
    }

    @Test
    void vendoredAdminLibrariesAreServed() throws Exception {
        List<String> urls = List.of(
                "/admin/vendor/vue/vue.global.prod.js",
                "/admin/vendor/element-plus/index.full.min.js",
                "/admin/vendor/element-plus/index.css",
                "/admin/vendor/element-plus/locale/zh-cn.min.js",
                "/admin/vendor/element-plus-icons/index.iife.min.js",
                "/admin/vendor/echarts/echarts.min.js");
        for (String url : urls) {
            MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
            assertThat(result.getResponse().getContentAsByteArray()).as(url).hasSizeGreaterThan(1000);
        }
    }

    // ------------------------------------------------------------------ App 语音识别

    @Test
    void asrRequiresAppLoginAndUsesMockWithoutKey() throws Exception {
        MockMultipartFile audio = new MockMultipartFile("audio", "voice.wav", "audio/wav", WavAudio.silence(16000, 800));
        mvc.perform(multipart("/pestai/v1/ai/asr").file(audio))
                .andExpect(jsonPath("$.code").value(40101));

        User auth = new User();
        auth.setId(10002L);
        auth.setTokenVersion(0);
        auth.setStatus(User.STATUS_NORMAL);
        when(userMapper.selectAuthInfo(10002L)).thenReturn(auth);
        String bearer = "Bearer " + tokens.issue(10002L, 0);
        mvc.perform(multipart("/pestai/v1/ai/asr").file(audio).header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.text").value(MockAsrClient.MOCK_TEXT));
        MockMultipartFile mp3 = new MockMultipartFile("audio", "voice.mp3", "audio/mpeg", "ID3xxxxxxxxxxxxxxx".getBytes());
        mvc.perform(multipart("/pestai/v1/ai/asr").file(mp3).header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("只支持 WAV 格式的录音"));
        mvc.perform(multipart("/pestai/v1/ai/asr").header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(40001));
    }
}
