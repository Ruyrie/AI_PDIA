package com.heban.pestai.record.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.heban.pestai.common.format.TimeFormatter;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.network.ApiResponse;
import com.heban.pestai.network.PageVO;
import com.heban.pestai.network.di.NetworkModule;

import org.junit.Test;

import java.lang.reflect.Type;
import java.time.LocalDateTime;

/**
 * 用技术准备文档 2.6 的示例响应验证 Gson（与 NetworkModule 相同的下划线命名策略和日期格式）解析结果。
 */
public class RecordVoParseTest {

    private final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setDateFormat(NetworkModule.DATE_FORMAT)
            .create();

    private static final String LIST_JSON = "{\"code\":0,\"message\":\"ok\",\"data\":{\"list\":["
            + "{\"id\":1024,\"mode\":\"PEST\",\"image_url\":\"http://10.0.2.2:8080/files/rec/20261013/5e8b1c2d.jpg\","
            + "\"status\":\"SUCCESS\",\"top_name\":\"黄瓜霜霉病\",\"confidence\":0.92,\"severity\":\"中度\","
            + "\"created_at\":\"2026-10-13 14:20:05\"},"
            + "{\"id\":1023,\"mode\":\"PEST\",\"image_url\":\"http://10.0.2.2:8080/files/rec/20261013/9a4f7e10.jpg\","
            + "\"status\":\"FAILED\",\"fail_reason\":\"TIMEOUT\",\"created_at\":\"2026-10-13 11:02:41\"}"
            + "],\"total\":6,\"has_more\":false}}";

    @Test
    public void parsesPageOfRecordItems() {
        Type type = new TypeToken<ApiResponse<PageVO<RecordItemVO>>>() {
        }.getType();
        ApiResponse<PageVO<RecordItemVO>> response = gson.fromJson(LIST_JSON, type);

        assertEquals(0, response.getCode());
        PageVO<RecordItemVO> page = response.getData();
        assertEquals(6, page.getTotal());
        assertFalse(page.isHasMore());
        assertEquals(2, page.getList().size());

        RecordItemVO first = page.getList().get(0);
        assertEquals(1024, first.getId());
        assertEquals("PEST", first.getMode());
        assertEquals("http://10.0.2.2:8080/files/rec/20261013/5e8b1c2d.jpg", first.getImageUrl());
        assertEquals("SUCCESS", first.getStatus());
        assertEquals("黄瓜霜霉病", first.getTopName());
        assertEquals(0.92, first.getConfidence(), 1e-9);
        assertEquals("中度", first.getSeverity());
        assertNull(first.getFailReason());
        assertEquals(LocalDateTime.of(2026, 10, 13, 14, 20, 5), TimeFormatter.toLocal(first.getCreatedAt()));

        RecordItemVO failed = page.getList().get(1);
        assertEquals(1023, failed.getId());
        assertEquals("FAILED", failed.getStatus());
        assertEquals("TIMEOUT", failed.getFailReason());
        assertNull(failed.getTopName());
        assertNull(failed.getConfidence());
        assertNull(failed.getSeverity());
        assertEquals(LocalDateTime.of(2026, 10, 13, 11, 2, 41), TimeFormatter.toLocal(failed.getCreatedAt()));
    }

    @Test
    public void summaryIgnoresRecognitionDetailFields() {
        // GET recognitions/{id} 返回 RecognitionVO，App 端用 RecordItemVO 接收，多出的字段被忽略
        String json = "{\"code\":0,\"message\":\"ok\",\"data\":{\"id\":1024,\"mode\":\"PEST\",\"status\":\"SUCCESS\","
                + "\"top_name\":\"黄瓜霜霉病\",\"confidence\":0.92,\"severity\":\"中度\",\"category\":\"真菌病害\","
                + "\"spread_fast\":true,\"evidence\":[\"叶面黄斑\"],\"others\":[{\"name\":\"黄瓜白粉病\",\"confidence\":0.05}],"
                + "\"model_name\":\"qwen-vl\",\"created_at\":\"2026-10-13 14:20:05\"}}";
        Type type = new TypeToken<ApiResponse<RecordItemVO>>() {
        }.getType();
        ApiResponse<RecordItemVO> response = gson.fromJson(json, type);
        RecordItemVO vo = response.getData();
        assertEquals(1024, vo.getId());
        assertEquals("黄瓜霜霉病", vo.getTopName());
        assertEquals("中度", vo.getSeverity());
    }

    @Test
    public void deleteResponseWithoutData() {
        Type type = new TypeToken<ApiResponse<Void>>() {
        }.getType();
        ApiResponse<Void> response = gson.fromJson("{\"code\":0,\"message\":\"ok\"}", type);
        assertEquals(0, response.getCode());
        assertNull(response.getData());

        ApiResponse<Void> notFound = gson.fromJson("{\"code\":40001,\"message\":\"记录不存在\"}", type);
        assertEquals(40001, notFound.getCode());
        assertEquals("记录不存在", notFound.getMessage());
    }
}
