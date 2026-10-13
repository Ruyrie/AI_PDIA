package com.heban.pestai.recognize.testing;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.heban.pestai.common.vo.CandidateVO;
import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.network.di.NetworkModule;

import java.util.Arrays;
import java.util.Calendar;

/**
 * 与后端一致的示例数据（模块05 / 06 技术准备文档 2.6：黄瓜霜霉病 92%）。
 */
public final class Samples {

    /** 与 NetworkModule 相同配置的 Gson */
    public static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setDateFormat(NetworkModule.DATE_FORMAT)
            .create();

    public static final String RESPONSE_JSON = "{\"code\":0,\"message\":\"ok\",\"data\":{"
            + "\"id\":1024,\"mode\":\"PEST\","
            + "\"image_url\":\"http://10.0.2.2:8080/files/rec/20261013/abc.jpg\","
            + "\"status\":\"SUCCESS\",\"top_name\":\"黄瓜霜霉病\",\"confidence\":0.92,\"severity\":\"中度\","
            + "\"fail_reason\":null,\"created_at\":\"2026-10-13 14:20:00\",\"category\":\"卵菌病害\","
            + "\"part\":\"叶部\",\"spread_fast\":true,"
            + "\"evidence\":\"叶面出现受叶脉限制的黄色多角形病斑，叶背有灰黑色霉层，与霜霉病典型症状一致。建议 3 天内处理，防止扩散到整棚。\","
            + "\"urgency_days\":3,"
            + "\"others\":[{\"name\":\"黄瓜细菌性角斑病\",\"confidence\":0.06},{\"name\":\"缺镁症（生理性）\",\"confidence\":0.02}],"
            + "\"model_name\":\"DeepSeek\"}}";

    private Samples() {
    }

    public static RecognitionVO cucumber() {
        RecognitionVO vo = new RecognitionVO();
        vo.setId(1024);
        vo.setMode("PEST");
        vo.setImageUrl("http://10.0.2.2:8080/files/rec/20261013/abc.jpg");
        vo.setStatus("SUCCESS");
        vo.setTopName("黄瓜霜霉病");
        vo.setConfidence(0.92);
        vo.setSeverity("中度");
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.OCTOBER, 13, 14, 20, 0);
        c.set(Calendar.MILLISECOND, 0);
        vo.setCreatedAt(c.getTime());
        vo.setCategory("卵菌病害");
        vo.setPart("叶部");
        vo.setSpreadFast(true);
        vo.setEvidence("叶面出现受叶脉限制的黄色多角形病斑，叶背有灰黑色霉层，与霜霉病典型症状一致。");
        vo.setUrgencyDays(3);
        vo.setOthers(Arrays.asList(candidate("黄瓜细菌性角斑病", 0.06), candidate("缺镁症（生理性）", 0.02)));
        vo.setModelName("DeepSeek");
        return vo;
    }

    public static RecognitionVO crop() {
        RecognitionVO vo = cucumber();
        vo.setId(2048);
        vo.setMode("CROP");
        vo.setTopName("马唐（杂草）");
        vo.setConfidence(0.9);
        vo.setSeverity(null);
        vo.setCategory("杂草");
        vo.setPart(null);
        vo.setSpreadFast(true);
        return vo;
    }

    public static RecognitionVO failed() {
        RecognitionVO vo = new RecognitionVO();
        vo.setId(77);
        vo.setMode("PEST");
        vo.setImageUrl("http://10.0.2.2:8080/files/rec/20261013/bad.jpg");
        vo.setStatus("FAILED");
        vo.setFailReason("TIMEOUT");
        return vo;
    }

    public static CandidateVO candidate(String name, double confidence) {
        CandidateVO c = new CandidateVO();
        c.setName(name);
        c.setConfidence(confidence);
        return c;
    }
}
