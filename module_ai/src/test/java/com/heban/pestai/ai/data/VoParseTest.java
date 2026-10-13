package com.heban.pestai.ai.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.heban.pestai.common.vo.ChatMessageVO;
import com.heban.pestai.common.vo.SessionDetailVO;
import com.heban.pestai.common.vo.SessionItemVO;
import com.heban.pestai.common.vo.SessionVO;
import com.heban.pestai.common.vo.SuggestionVO;
import com.heban.pestai.common.vo.UploadVO;
import com.heban.pestai.network.ApiResponse;
import com.heban.pestai.network.PageVO;

import org.junit.Test;

import java.lang.reflect.Type;
import java.util.List;

/** 与 NetworkModule 相同的 Gson 配置：下划线字段能正确映射 */
public class VoParseTest {

    private final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setDateFormat("yyyy-MM-dd HH:mm:ss")
            .create();

    @Test
    public void sessionVO() {
        SessionVO vo = gson.fromJson("{\"session_id\":512,\"record_id\":2036,\"title\":\"追问：黄瓜霜霉病\"}",
                SessionVO.class);
        assertEquals(512, vo.getSessionId());
        assertEquals(Long.valueOf(2036), vo.getRecordId());
        assertEquals("追问：黄瓜霜霉病", vo.getTitle());
    }

    @Test
    public void sessionVO_nullRecordId() {
        SessionVO vo = gson.fromJson("{\"session_id\":513,\"record_id\":null,\"title\":null}", SessionVO.class);
        assertEquals(513, vo.getSessionId());
        assertNull(vo.getRecordId());
        assertNull(vo.getTitle());
    }

    @Test
    public void uploadVO() {
        UploadVO vo = gson.fromJson("{\"url\":\"http://x/files/chat/20261013/8f2c.jpg\"}", UploadVO.class);
        assertEquals("http://x/files/chat/20261013/8f2c.jpg", vo.getUrl());
    }

    @Test
    public void suggestionList() {
        Type type = new TypeToken<ApiResponse<List<SuggestionVO>>>() {
        }.getType();
        ApiResponse<List<SuggestionVO>> response = gson.fromJson(
                "{\"code\":0,\"message\":\"ok\",\"data\":[{\"id\":1,\"text\":\"番茄叶片发黄卷曲是什么原因？\"}]}", type);
        assertTrue(response.isSuccess());
        assertEquals(1, response.getData().size());
        assertEquals(1, response.getData().get(0).getId());
        assertEquals("番茄叶片发黄卷曲是什么原因？", response.getData().get(0).getText());
    }

    @Test
    public void sendMessageReq_serializesSnakeCase_andSkipsNullImage() {
        String json = gson.toJson(new SendMessageReq("这个病怎么处理？", null, false));
        assertEquals("{\"content\":\"这个病怎么处理？\",\"regenerate\":false}", json);
        String withImage = gson.toJson(new SendMessageReq("看图", "http://x/a.jpg", true));
        assertTrue(withImage.contains("\"image_url\":\"http://x/a.jpg\""));
        assertTrue(withImage.contains("\"regenerate\":true"));
    }

    @Test
    public void createSessionReq() {
        assertEquals("{\"record_id\":2036}", gson.toJson(new CreateSessionReq(2036L)));
        String empty = gson.toJson(new CreateSessionReq(null));
        assertEquals("{}", empty);
        assertFalse(empty.contains("record_id"));
    }

    @Test
    public void sessionPage() {
        Type type = new TypeToken<ApiResponse<PageVO<SessionItemVO>>>() {
        }.getType();
        ApiResponse<PageVO<SessionItemVO>> response = gson.fromJson("{\"code\":0,\"message\":\"ok\",\"data\":{"
                + "\"list\":[{\"session_id\":512,\"record_id\":2036,\"title\":\"追问：黄瓜霜霉病\","
                + "\"last_message\":\"建议 3 天内处理\",\"message_count\":4,\"updated_at\":\"2026-10-13 14:20:00\"},"
                + "{\"session_id\":513,\"record_id\":null,\"title\":\"图片提问\",\"last_message\":null,"
                + "\"message_count\":2,\"updated_at\":\"2026-10-12 09:00:00\"}],"
                + "\"total\":2,\"has_more\":false}}", type);
        PageVO<SessionItemVO> page = response.getData();
        assertEquals(2, page.getList().size());
        assertFalse(page.isHasMore());
        SessionItemVO first = page.getList().get(0);
        assertEquals(512, first.getSessionId());
        assertEquals(Long.valueOf(2036), first.getRecordId());
        assertEquals("建议 3 天内处理", first.getLastMessage());
        assertEquals(4, first.getMessageCount());
        assertTrue(first.getUpdatedAt() != null);
        assertNull(page.getList().get(1).getRecordId());
    }

    @Test
    public void sessionDetail_withReasoning() {
        SessionDetailVO vo = gson.fromJson("{\"session_id\":512,\"record_id\":null,\"title\":\"新对话\","
                + "\"record\":null,\"messages\":[{\"role\":\"user\",\"content\":\"蚜虫怎么防？\","
                + "\"image_url\":null,\"created_at\":\"2026-10-13 14:20:00\"},"
                + "{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"先考虑药剂\","
                + "\"reasoning_ms\":null,\"created_at\":\"2026-10-13 14:20:05\"},"
                + "{\"role\":\"assistant\",\"content\":\"可以用吡虫啉\",\"reasoning_content\":\"想一想\","
                + "\"reasoning_ms\":12400,\"created_at\":\"2026-10-13 14:21:00\"}]}", SessionDetailVO.class);
        assertEquals(512, vo.getSessionId());
        assertNull(vo.getRecord());
        List<ChatMessageVO> messages = vo.getMessages();
        assertEquals(3, messages.size());
        assertEquals("先考虑药剂", messages.get(1).getReasoningContent());
        assertNull(messages.get(1).getReasoningMs());
        assertEquals(Integer.valueOf(12400), messages.get(2).getReasoningMs());
    }
}
