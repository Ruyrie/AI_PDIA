package com.heban.pestai.common.vo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.junit.Test;

/**
 * CandidateVO：新增的 reason / difference 字段按下划线命名解析；旧记录没有这两个字段时为 null；
 * equals / hashCode 包含新字段（R01「其他可能」列表的 DiffUtil 依赖它判断内容是否变化）。
 */
public class CandidateVOTest {

    /** 与 NetworkModule 相同的命名策略 */
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    @Test
    public void parse_withReasonAndDifference() {
        CandidateVO vo = GSON.fromJson("{\"name\":\"黄瓜细菌性角斑病\",\"confidence\":0.06,"
                + "\"reason\":\"病斑同样受叶脉限制呈多角形\","
                + "\"difference\":\"角斑病叶背为白色菌脓而非灰黑色霉层\"}", CandidateVO.class);
        assertEquals("黄瓜细菌性角斑病", vo.getName());
        assertEquals(0.06, vo.getConfidence(), 1e-9);
        assertEquals("病斑同样受叶脉限制呈多角形", vo.getReason());
        assertEquals("角斑病叶背为白色菌脓而非灰黑色霉层", vo.getDifference());
    }

    @Test
    public void parse_oldRecordWithoutExplanation() {
        CandidateVO vo = GSON.fromJson("{\"name\":\"缺镁症（生理性）\",\"confidence\":0.02}", CandidateVO.class);
        assertEquals("缺镁症（生理性）", vo.getName());
        assertNull(vo.getReason());
        assertNull(vo.getDifference());
    }

    @Test
    public void parse_explicitNulls() {
        CandidateVO vo = GSON.fromJson("{\"name\":\"a\",\"confidence\":0.1,\"reason\":null,\"difference\":null}",
                CandidateVO.class);
        assertNull(vo.getReason());
        assertNull(vo.getDifference());
    }

    @Test
    public void roundTrip_keepsNewFields() {
        CandidateVO vo = candidate("a", 0.1, "r", "d");
        String json = GSON.toJson(vo);
        assertTrue(json.contains("\"reason\":\"r\""));
        assertTrue(json.contains("\"difference\":\"d\""));
        assertEquals(vo, GSON.fromJson(json, CandidateVO.class));
    }

    @Test
    public void equalsAndHashCode_includeNewFields() {
        CandidateVO base = candidate("a", 0.1, "r", "d");
        assertEquals(base, candidate("a", 0.1, "r", "d"));
        assertEquals(base.hashCode(), candidate("a", 0.1, "r", "d").hashCode());
        assertNotEquals(base, candidate("a", 0.1, "r2", "d"));
        assertNotEquals(base, candidate("a", 0.1, "r", null));
        assertNotEquals(base, candidate("a", 0.2, "r", "d"));
        assertNotEquals(base, candidate("b", 0.1, "r", "d"));
    }

    private static CandidateVO candidate(String name, double confidence, String reason, String difference) {
        CandidateVO vo = new CandidateVO();
        vo.setName(name);
        vo.setConfidence(confidence);
        vo.setReason(reason);
        vo.setDifference(difference);
        return vo;
    }
}
