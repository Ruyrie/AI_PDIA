package com.heban.pestai.server;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.security.JwtTokenProvider;
import com.heban.pestai.server.service.AiSessionService;
import com.heban.pestai.server.vo.ChatMessageVO;
import com.heban.pestai.server.vo.PageVO;
import com.heban.pestai.server.vo.RecordItemVO;
import com.heban.pestai.server.vo.SessionDetailVO;
import com.heban.pestai.server.vo.SessionItemVO;

/**
 * 历史对话三个接口的接口层测试：启动完整 Spring 容器，AiSessionService 与 UserMapper Mock，
 * 数据源指向不可达地址，确保不会连接本机数据库。业务规则（归属、分页、预览、删除）见 AiSessionServiceTest。
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
        "pestai.jwt.secret=integration-test-secret-0123456789abcdef"
})
@AutoConfigureMockMvc
class AiSessionApiTest {

    private static final long USER_ID = 10001L;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 13, 14, 23, 5);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtTokenProvider tokens;

    @MockBean
    private UserMapper userMapper;
    @MockBean
    private AiSessionService aiSessionService;

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
    void historyApisRequireLogin() throws Exception {
        mvc.perform(get("/pestai/v1/ai/sessions")).andExpect(jsonPath("$.code").value(40101));
        mvc.perform(get("/pestai/v1/ai/sessions/512")).andExpect(jsonPath("$.code").value(40101));
        mvc.perform(delete("/pestai/v1/ai/sessions/512")).andExpect(jsonPath("$.code").value(40101));
        verifyNoInteractions(aiSessionService);
    }

    @Test
    void listUsesDefaultsAndReturnsSnakeCasePage() throws Exception {
        SessionItemVO item = new SessionItemVO(512L, 2036L, "追问：黄瓜霜霉病", "[图片]叶背有灰黑色霉层", 4, AT);
        when(aiSessionService.page(USER_ID, 1, 20)).thenReturn(new PageVO<>(List.of(item), 21L, true));

        mvc.perform(get("/pestai/v1/ai/sessions").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.has_more").value(true))
                .andExpect(jsonPath("$.data.list[0].session_id").value(512))
                .andExpect(jsonPath("$.data.list[0].record_id").value(2036))
                .andExpect(jsonPath("$.data.list[0].title").value("追问：黄瓜霜霉病"))
                .andExpect(jsonPath("$.data.list[0].last_message").value("[图片]叶背有灰黑色霉层"))
                .andExpect(jsonPath("$.data.list[0].message_count").value(4))
                .andExpect(jsonPath("$.data.list[0].updated_at").value("2026-10-13 14:23:05"));

        when(aiSessionService.page(USER_ID, 3, 50)).thenReturn(PageVO.empty(21L));
        mvc.perform(get("/pestai/v1/ai/sessions").param("page", "3").param("size", "50").header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list.length()").value(0));
        verify(aiSessionService).page(USER_ID, 3, 50);
    }

    @Test
    void listRejectsOutOfRangePaging() throws Exception {
        String[][] bad = {{"page", "0"}, {"size", "0"}, {"size", "51"}, {"page", "abc"}, {"size", "-1"}};
        for (String[] param : bad) {
            mvc.perform(get("/pestai/v1/ai/sessions").param(param[0], param[1]).header("Authorization", bearer))
                    .andExpect(jsonPath("$.code").value(40001));
        }
        verify(aiSessionService, never()).page(anyLong(), anyInt(), anyInt());
    }

    @Test
    void detailReturnsRecordAndMessagesWithReasoning() throws Exception {
        RecordItemVO record = new RecordItemVO(2036L, "PEST", "http://10.0.2.2:8080/files/rec/20261013/r.jpg", "SUCCESS",
                "黄瓜霜霉病", new BigDecimal("0.92"), "中度", null, AT);
        SessionDetailVO detail = new SessionDetailVO(512L, 2036L, "追问：黄瓜霜霉病", record, List.of(
                new ChatMessageVO("user", "这个病怎么处理？", "http://10.0.2.2:8080/files/chat/20261013/q.jpg", AT, null, null),
                new ChatMessageVO("assistant", "处理建议……", null, AT, "先看病斑", 12345)));
        when(aiSessionService.detail(USER_ID, 512L)).thenReturn(detail);

        mvc.perform(get("/pestai/v1/ai/sessions/512").header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.session_id").value(512))
                .andExpect(jsonPath("$.data.record.top_name").value("黄瓜霜霉病"))
                .andExpect(jsonPath("$.data.record.confidence").value(0.92))
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].image_url").value("http://10.0.2.2:8080/files/chat/20261013/q.jpg"))
                .andExpect(jsonPath("$.data.messages[0].reasoning_content").isEmpty())
                .andExpect(jsonPath("$.data.messages[1].reasoning_content").value("先看病斑"))
                .andExpect(jsonPath("$.data.messages[1].reasoning_ms").value(12345))
                .andExpect(jsonPath("$.data.messages[1].created_at").value("2026-10-13 14:23:05"));
    }

    @Test
    void detailAndDeleteOfOthersSessionAre40001() throws Exception {
        when(aiSessionService.detail(USER_ID, 700L)).thenThrow(BizException.param("会话不存在"));
        mvc.perform(get("/pestai/v1/ai/sessions/700").header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("会话不存在"));

        doThrow(BizException.param("无权删除该对话")).when(aiSessionService).delete(USER_ID, 700L);
        mvc.perform(delete("/pestai/v1/ai/sessions/700").header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("无权删除该对话"));
    }

    @Test
    void deleteReturnsNullDataAndNonNumericIdIsRejected() throws Exception {
        mvc.perform(delete("/pestai/v1/ai/sessions/512").header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(aiSessionService).delete(USER_ID, 512L);

        mvc.perform(get("/pestai/v1/ai/sessions/abc").header("Authorization", bearer))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/pestai/v1/ai/sessions/0").header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(40001));
        verify(aiSessionService, never()).detail(anyLong(), org.mockito.ArgumentMatchers.eq(0L));
    }
}
