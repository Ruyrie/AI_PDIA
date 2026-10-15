package com.heban.pestai.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.heban.pestai.server.admin.mapper.AdminMapper;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiSession;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;
import com.heban.pestai.server.mapper.RecognitionMapper;
import com.heban.pestai.server.mapper.SystemConfigMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.security.JwtTokenProvider;
import com.heban.pestai.server.service.AiMessageTxService;

/**
 * 启动完整的 Spring 容器（不连接数据库：用到的 Mapper 全部 Mock；未配置大模型 Key，使用 Mock 客户端），
 * 验证接口前缀、登录拦截与白名单、统一响应 JSON、流式响应格式、/files/** 静态映射。
 */
@SpringBootTest(properties = {
        "vision.api-key=",
        "chat.api-key=",
        "sms.provider=log",
        "pestai.file-root=target/test-files",
        "pestai.public-base-url=http://10.0.2.2:8080",
        "pestai.jwt.secret=integration-test-secret-0123456789abcdef",
        "pestai.mock.vision-delay-ms=0",
        "pestai.mock.image-bad-percent=0",
        "pestai.mock.chat-chunk-delay-ms=0"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {

    private static final long USER_ID = 10001L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtTokenProvider tokens;

    @MockBean
    private UserMapper userMapper;
    /** 后台管理员表与后台配置表：启动时会读取，测试中不访问数据库 */
    @MockBean
    private AdminMapper adminMapper;
    @MockBean
    private SystemConfigMapper systemConfigMapper;
    @MockBean
    private RecognitionMapper recognitionMapper;
    @MockBean
    private AiSessionMapper aiSessionMapper;
    @MockBean
    private AiMessageMapper aiMessageMapper;
    @MockBean
    private AiMessageTxService aiMessageTxService;

    private String bearer;

    @BeforeEach
    void setUp() {
        User auth = new User();
        auth.setId(USER_ID);
        auth.setTokenVersion(0);
        auth.setStatus(User.STATUS_NORMAL);
        when(userMapper.selectAuthInfo(USER_ID)).thenReturn(auth);
        bearer = "Bearer " + tokens.issue(USER_ID, 0);
    }

    @Test
    void protectedApiWithoutTokenReturns40101Json() throws Exception {
        mvc.perform(get("/pestai/v1/recognitions/recent"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.message").value("未登录或登录已过期，请重新登录"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void outdatedTokenVersionOrDisabledAccountIs40101() throws Exception {
        String old = "Bearer " + tokens.issue(USER_ID, 0);
        User bumped = new User();
        bumped.setId(USER_ID);
        bumped.setTokenVersion(1);
        bumped.setStatus(User.STATUS_NORMAL);
        when(userMapper.selectAuthInfo(USER_ID)).thenReturn(bumped);
        mvc.perform(get("/pestai/v1/recognitions/recent").header("Authorization", old))
                .andExpect(jsonPath("$.code").value(40101));

        User disabled = new User();
        disabled.setId(USER_ID);
        disabled.setTokenVersion(0);
        disabled.setStatus(User.STATUS_DISABLED);
        when(userMapper.selectAuthInfo(USER_ID)).thenReturn(disabled);
        mvc.perform(get("/pestai/v1/recognitions/recent").header("Authorization", old))
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void recentRecognitionsAreSnakeCaseWithFullImageUrl() throws Exception {
        Recognition ok = new Recognition();
        ok.setId(1024L);
        ok.setMode("PEST");
        ok.setImageUrl("rec/20261010/3f2b.jpg");
        ok.setStatus("SUCCESS");
        ok.setTopName("黄瓜霜霉病");
        ok.setConfidence(new BigDecimal("0.920"));
        ok.setSeverity("中度");
        ok.setCreatedAt(LocalDateTime.of(2026, 10, 10, 14, 20, 0));
        Recognition failed = new Recognition();
        failed.setId(1023L);
        failed.setMode("PEST");
        failed.setImageUrl("rec/20261010/9a4f.jpg");
        failed.setStatus("FAILED");
        failed.setFailReason("TIMEOUT");
        failed.setCreatedAt(LocalDateTime.of(2026, 10, 10, 11, 2, 41));
        when(recognitionMapper.selectRecent(USER_ID, 3)).thenReturn(List.of(ok, failed));

        mvc.perform(get("/pestai/v1/recognitions/recent").param("size", "3").header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data[0].id").value(1024))
                .andExpect(jsonPath("$.data[0].image_url").value("http://10.0.2.2:8080/files/rec/20261010/3f2b.jpg"))
                .andExpect(jsonPath("$.data[0].top_name").value("黄瓜霜霉病"))
                .andExpect(jsonPath("$.data[0].confidence").value(0.92))
                .andExpect(jsonPath("$.data[0].created_at").value("2026-10-10 14:20:00"))
                .andExpect(jsonPath("$.data[1].fail_reason").value("TIMEOUT"))
                .andExpect(jsonPath("$.data[1].top_name").isEmpty());
    }

    @Test
    void parameterErrorsAre40001() throws Exception {
        mvc.perform(get("/pestai/v1/recognitions/recent").param("size", "11").header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(40001));
        mvc.perform(get("/pestai/v1/recognitions").param("mode", "FOO").header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(40001));
        mvc.perform(get("/pestai/v1/recognitions/abc").header("Authorization", bearer))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void whitelistedSmsApiValidatesWithoutToken() throws Exception {
        mvc.perform(post("/pestai/v1/auth/sms/send").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"12345\",\"scene\":\"LOGIN\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("手机号格式不正确"));
        mvc.perform(post("/pestai/v1/auth/sms/send").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800005678\",\"scene\":\"FOO\"}"))
                .andExpect(jsonPath("$.code").value(40001));
        mvc.perform(post("/pestai/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800005678\",\"code\":\"123456\",\"password\":\"12345678\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("密码需为 8–20 位，并同时包含字母和数字"));
    }

    @Test
    void removedAdminApisAreNotWhitelistedAndNoLongerExist() throws Exception {
        // admin/** 不再单独放行：没有令牌按普通接口返回 40101，X-Admin-Key 不起任何作用
        mvc.perform(post("/pestai/v1/admin/appeals/SQ20261013001/approve").header("X-Admin-Key", "anything"))
                .andExpect(jsonPath("$.code").value(40101));
        // 登录后访问：接口已删除
        mvc.perform(post("/pestai/v1/admin/appeals/SQ20261013001/approve").header("Authorization", bearer))
                .andExpect(status().isNotFound());
        mvc.perform(post("/pestai/v1/auth/appeal").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadRecognitionWithMockVisionReturnsRecognitionVo() throws Exception {
        when(recognitionMapper.insert(any())).thenAnswer(inv -> {
            ((Recognition) inv.getArgument(0)).setId(2036L);
            return 1;
        });
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 9, 9, 9};
        MockMultipartFile image = new MockMultipartFile("image", "leaf.jpg", "image/jpeg", jpeg);

        mvc.perform(multipart("/pestai/v1/recognitions").file(image).param("mode", "PEST")
                        .header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(2036))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.model_name").value("通义千问"))
                .andExpect(jsonPath("$.data.others").isArray())
                .andExpect(jsonPath("$.data.spread_fast").isBoolean());

        mvc.perform(multipart("/pestai/v1/recognitions").file(image).param("mode", "FOO")
                        .header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void chatMessageStreamsSseLinesAndSavesAnswer() throws Exception {
        AiSession session = new AiSession();
        session.setId(5L);
        session.setUserId(USER_ID);
        session.setTitle("蚜虫怎么防治？");
        when(aiSessionMapper.selectById(5L)).thenReturn(session);
        when(aiMessageTxService.saveQuestion(any(), anyString(), any(), anyBoolean())).thenReturn(77L);
        when(aiMessageMapper.selectRecent(eq(5L), eq(77L), anyInt())).thenReturn(List.of());

        MvcResult started = mvc.perform(post("/pestai/v1/ai/sessions/5/messages")
                        .header("Authorization", bearer)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"蚜虫怎么防治？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(10_000);
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Buffering", "no"));

        String body = started.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(started.getResponse().getContentType()).startsWith("text/event-stream");
        // Mock 问答先推思考片段（event: reasoning + data），再推正文（只有 data），最后 [DONE]
        assertThat(body).startsWith("event: reasoning\ndata: ").endsWith("data: [DONE]\n\n");
        StringBuilder rebuilt = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        boolean contentStarted = false;
        for (String event : body.split("\n\n")) {
            String[] lines = event.split("\n");
            if (lines.length == 2) {
                assertThat(lines[0]).isEqualTo("event: reasoning");
                assertThat(lines[1]).startsWith("data: ");
                assertThat(contentStarted).as("思考片段在正文之前").isFalse();
                reasoning.append(lines[1].substring("data: ".length()).replace("\\n", "\n"));
                continue;
            }
            assertThat(lines).hasSize(1);
            assertThat(lines[0]).startsWith("data: ");
            String data = lines[0].substring("data: ".length());
            if (!"[DONE]".equals(data)) {
                contentStarted = true;
                rebuilt.append(data.replace("\\n", "\n"));
            }
        }
        assertThat(reasoning.toString()).contains("蚜虫怎么防治");
        assertThat(rebuilt.toString()).contains("黄色粘虫板").contains("\n");

        ArgumentCaptor<AiMessage> saved = ArgumentCaptor.forClass(AiMessage.class);
        verify(aiMessageMapper).insert(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo("assistant");
        assertThat(saved.getValue().getContent()).isEqualTo(rebuilt.toString());
        assertThat(saved.getValue().getReasoningContent()).isEqualTo(reasoning.toString());
        assertThat(saved.getValue().getReasoningMs()).isNotNull().isNotNegative();
        verify(aiSessionMapper).touch(eq(5L), any());
    }

    @Test
    void chatErrorBeforeStreamIsPlainJson() throws Exception {
        when(aiSessionMapper.selectById(6L)).thenReturn(null);
        mvc.perform(post("/pestai/v1/ai/sessions/6/messages")
                        .header("Authorization", bearer)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("会话不存在"));

        mvc.perform(post("/pestai/v1/ai/sessions/6/messages")
                        .header("Authorization", bearer)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void imageOnlyQuestionWithSeveralImagesStreamsAndImageRulesAreJsonErrors() throws Exception {
        AiSession session = new AiSession();
        session.setId(7L);
        session.setUserId(USER_ID);
        when(aiSessionMapper.selectById(7L)).thenReturn(session);
        when(aiMessageTxService.saveQuestion(any(), anyString(), any(), anyBoolean())).thenReturn(78L);
        when(aiMessageMapper.selectRecent(eq(7L), eq(78L), anyInt())).thenReturn(List.of());
        Path dir = Path.of("target/test-files/chat/20261014");
        Files.createDirectories(dir);
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 7, 7};
        Files.write(dir.resolve("multi-a.jpg"), jpeg);
        Files.write(dir.resolve("multi-b.jpg"), jpeg);
        String base = "http://10.0.2.2:8080/files/chat/20261014/";

        MvcResult started = mvc.perform(post("/pestai/v1/ai/sessions/7/messages")
                        .header("Authorization", bearer)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\",\"image_urls\":[\"" + base + "multi-a.jpg\",\""
                                + base + "multi-b.jpg\"]}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(10_000);
        String body = started.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).endsWith("data: [DONE]\n\n");
        StringBuilder reasoning = new StringBuilder();
        for (String event : body.split("\n\n")) {
            if (event.startsWith("event: reasoning\ndata: ")) {
                reasoning.append(event.substring("event: reasoning\ndata: ".length()));
            }
        }
        // Mock 模型收到了两张图片和多图的默认问题
        assertThat(reasoning.toString()).contains("用户发来 2 张照片").contains("请帮我看看这些照片里的作物");
        verify(aiMessageTxService).saveQuestion(any(), eq(""),
                eq(List.of("chat/20261014/multi-a.jpg", "chat/20261014/multi-b.jpg")), eq(false));

        StringBuilder ten = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            ten.append(i == 0 ? "" : ",").append("\"").append(base).append("multi-").append(i).append(".jpg\"");
        }
        String[][] bad = {
                {"{\"content\":\"看看\",\"image_urls\":[" + ten + "]}", "最多上传 9 张图片"},
                {"{\"content\":\" \",\"image_urls\":[]}", "请输入问题"},
                {"{\"image_url\":\"\"}", "请输入问题"},
                {"{\"content\":\"看看\",\"image_urls\":[\"" + base + "missing.jpg\"]}", "图片地址无效，请重新上传图片"},
                {"{\"content\":\"" + "长".repeat(501) + "\"}", "问题最多 500 字"}};
        for (String[] c : bad) {
            mvc.perform(post("/pestai/v1/ai/sessions/7/messages")
                            .header("Authorization", bearer)
                            .accept(MediaType.TEXT_EVENT_STREAM)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(c[0]))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value(c[1]));
        }
        verify(aiMessageTxService).saveQuestion(any(), anyString(), any(), anyBoolean());
    }

    @Test
    void suggestionsComeFromSeasonPool() throws Exception {
        when(userMapper.selectCropsById(USER_ID)).thenReturn("番茄,黄瓜,小麦");
        mvc.perform(get("/pestai/v1/ai/suggestions").header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").isNumber())
                .andExpect(jsonPath("$.data[0].text").isString())
                .andExpect(jsonPath("$.data[0].crop").isString());
    }

    @Test
    void filesAreServedFromFileRoot() throws Exception {
        Path dir = Path.of("target/test-files/rec/20260101");
        Files.createDirectories(dir);
        byte[] bytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2};
        Files.write(dir.resolve("served.jpg"), bytes);

        mvc.perform(get("/files/rec/20260101/served.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
        mvc.perform(get("/files/rec/20260101/missing.jpg"))
                .andExpect(status().isNotFound());
    }

    @Test
    void logoutNeedsTokenAndBumpsVersionWithoutDb() throws Exception {
        mvc.perform(post("/pestai/v1/auth/logout")).andExpect(jsonPath("$.code").value(40101));
        verify(userMapper, org.mockito.Mockito.never()).increaseTokenVersion(anyLong(), any());
    }
}
