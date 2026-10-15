package com.heban.pestai.recognize.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.reflect.TypeToken;
import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.network.ApiResponse;
import com.heban.pestai.recognize.testing.Samples;

import org.junit.Test;

import java.lang.reflect.Type;
import java.util.Calendar;

/**
 * VO 一致性：用与后端一致的示例响应解析 RecognitionVO；序列化为 record_json 再反序列化后字段不丢失。
 */
public class RecognitionVoJsonTest {

    @Test
    public void parseSampleResponse() {
        Type type = new TypeToken<ApiResponse<RecognitionVO>>() {
        }.getType();
        ApiResponse<RecognitionVO> response = Samples.GSON.fromJson(Samples.RESPONSE_JSON, type);
        assertTrue(response.isSuccess());
        RecognitionVO vo = response.getData();
        assertNotNull(vo);
        assertEquals(1024, vo.getId());
        assertEquals("PEST", vo.getMode());
        assertEquals("SUCCESS", vo.getStatus());
        assertEquals("黄瓜霜霉病", vo.getTopName());
        assertEquals(0.92, vo.getConfidence(), 1e-9);
        assertEquals("中度", vo.getSeverity());
        assertNull(vo.getFailReason());
        assertEquals("卵菌病害", vo.getCategory());
        assertEquals("叶部", vo.getPart());
        assertTrue(vo.isSpreadFast());
        assertEquals(Integer.valueOf(3), vo.getUrgencyDays());
        assertEquals("DeepSeek", vo.getModelName());
        assertEquals(2, vo.getOthers().size());
        assertEquals("黄瓜细菌性角斑病", vo.getOthers().get(0).getName());
        assertEquals(0.06, vo.getOthers().get(0).getConfidence(), 1e-9);
        // 旧记录没有判断依据
        assertNull(vo.getOthers().get(0).getReason());
        assertNull(vo.getOthers().get(0).getDifference());

        Calendar c = Calendar.getInstance();
        c.setTime(vo.getCreatedAt());
        assertEquals(2026, c.get(Calendar.YEAR));
        assertEquals(14, c.get(Calendar.HOUR_OF_DAY));
        assertEquals(20, c.get(Calendar.MINUTE));
    }

    @Test
    public void parseOthersWithExplanation() {
        String json = "{\"id\":1,\"status\":\"SUCCESS\",\"top_name\":\"黄瓜霜霉病\",\"others\":["
                + "{\"name\":\"黄瓜细菌性角斑病\",\"confidence\":0.06,"
                + "\"reason\":\"病斑同样受叶脉限制\",\"difference\":\"叶背有白色菌脓\"},"
                + "{\"name\":\"缺镁症（生理性）\",\"confidence\":0.02,\"reason\":null}]}";
        RecognitionVO vo = Samples.GSON.fromJson(json, RecognitionVO.class);
        assertEquals("病斑同样受叶脉限制", vo.getOthers().get(0).getReason());
        assertEquals("叶背有白色菌脓", vo.getOthers().get(0).getDifference());
        assertNull(vo.getOthers().get(1).getReason());
        assertNull(vo.getOthers().get(1).getDifference());
    }

    @Test
    public void recordJson_roundTrip_keepsExplanation() {
        RecognitionVO vo = Samples.explained();
        String json = Samples.GSON.toJson(vo);
        assertTrue(json.contains("\"reason\""));
        assertTrue(json.contains("\"difference\""));
        assertEquals(vo, Samples.GSON.fromJson(json, RecognitionVO.class));
    }

    @Test
    public void recordJson_roundTrip() {
        RecognitionVO vo = Samples.cucumber();
        String json = Samples.GSON.toJson(vo);
        assertTrue(json.contains("\"top_name\""));
        assertTrue(json.contains("\"spread_fast\""));
        RecognitionVO back = Samples.GSON.fromJson(json, RecognitionVO.class);
        assertEquals(vo, back);
    }
}
