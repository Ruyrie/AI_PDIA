package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.heban.pestai.server.common.Mode;
import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.vo.CandidateVO;

class VisionResultParserTest {

    private static final String CUCUMBER = """
            {"status":"SUCCESS","top_name":"黄瓜霜霉病","confidence":0.92,"severity":"中度","category":"卵菌病害",
             "part":"叶部","spread_fast":true,"evidence":"叶背有灰黑色霉层。建议 3 天内处理。","urgency_days":3,
             "others":[{"name":"黄瓜细菌性角斑病","confidence":0.06},{"name":"缺镁症（生理性）","confidence":0.02}]}
            """;

    @Test
    void parsesValidPestResult() throws Exception {
        ParsedVision p = VisionResultParser.parse(CUCUMBER, Mode.PEST);

        assertThat(p.status()).isEqualTo(RecognitionStatus.SUCCESS);
        assertThat(p.topName()).isEqualTo("黄瓜霜霉病");
        assertThat(p.confidence()).isEqualByComparingTo("0.920");
        assertThat(p.confidence().scale()).isEqualTo(3);
        assertThat(p.severity()).isEqualTo("中度");
        assertThat(p.category()).isEqualTo("卵菌病害");
        assertThat(p.part()).isEqualTo("叶部");
        assertThat(p.spreadFast()).isTrue();
        assertThat(p.urgencyDays()).isEqualTo(3);
        assertThat(p.others()).extracting(CandidateVO::name).containsExactly("黄瓜细菌性角斑病", "缺镁症（生理性）");
    }

    @Test
    void toleratesMarkdownFenceAndSurroundingText() throws Exception {
        String raw = "好的，识别结果如下：\n```json\n" + CUCUMBER + "\n```\n希望对你有帮助";
        assertThat(VisionResultParser.parse(raw, Mode.PEST).topName()).isEqualTo("黄瓜霜霉病");
    }

    @Test
    void imageBadNeedsNoOtherFields() throws Exception {
        ParsedVision p = VisionResultParser.parse("{\"status\":\"image_bad\"}", Mode.PEST);
        assertThat(p.status()).isEqualTo(RecognitionStatus.IMAGE_BAD);
        assertThat(p.isSuccess()).isFalse();
        assertThat(p.others()).isEmpty();
    }

    @Test
    void confidenceIsRoundedToThreeDecimals() throws Exception {
        String raw = CUCUMBER.replace("\"confidence\":0.92,", "\"confidence\":0.91666,");
        assertThat(VisionResultParser.parse(raw, Mode.PEST).confidence()).isEqualByComparingTo(new BigDecimal("0.917"));
    }

    @Test
    void rejectsOutOfRangeConfidence() {
        String raw = CUCUMBER.replace("\"confidence\":0.92,", "\"confidence\":92,");
        assertThatThrownBy(() -> VisionResultParser.parse(raw, Mode.PEST))
                .isInstanceOf(VisionResultParser.InvalidResultException.class);
    }

    @Test
    void rejectsSeverityOutsideFourLevelsInPestMode() {
        String raw = CUCUMBER.replace("\"severity\":\"中度\"", "\"severity\":\"严重\"");
        assertThatThrownBy(() -> VisionResultParser.parse(raw, Mode.PEST))
                .isInstanceOf(VisionResultParser.InvalidResultException.class);
    }

    @Test
    void cropModeClearsSeverity() throws Exception {
        String raw = CUCUMBER.replace("\"severity\":\"中度\"", "\"severity\":\"随便\"");
        assertThat(VisionResultParser.parse(raw, Mode.CROP).severity()).isNull();
    }

    @Test
    void rejectsMissingTopNameUnknownStatusAndGarbage() {
        assertThatThrownBy(() -> VisionResultParser.parse(CUCUMBER.replace("\"top_name\":\"黄瓜霜霉病\",", ""), Mode.PEST))
                .isInstanceOf(VisionResultParser.InvalidResultException.class);
        assertThatThrownBy(() -> VisionResultParser.parse("{\"status\":\"OK\"}", Mode.PEST))
                .isInstanceOf(VisionResultParser.InvalidResultException.class);
        assertThatThrownBy(() -> VisionResultParser.parse("抱歉，我无法识别", Mode.PEST))
                .isInstanceOf(VisionResultParser.InvalidResultException.class);
        assertThatThrownBy(() -> VisionResultParser.parse("{\"status\":", Mode.PEST))
                .isInstanceOf(VisionResultParser.InvalidResultException.class);
        assertThatThrownBy(() -> VisionResultParser.parse(null, Mode.PEST))
                .isInstanceOf(VisionResultParser.InvalidResultException.class);
    }

    @Test
    void othersAreFilteredSortedAndLimitedToThree() throws Exception {
        String raw = """
                {"status":"SUCCESS","top_name":"桃蚜","confidence":0.8,"severity":"较重",
                 "others":[{"name":"A","confidence":0.01},{"name":"B","confidence":0.09},
                           {"name":"","confidence":0.5},{"name":"C","confidence":1.5},
                           {"name":"D","confidence":0.05},{"name":"E","confidence":0.07},
                           {"name":"桃蚜","confidence":0.3}]}
                """;
        ParsedVision p = VisionResultParser.parse(raw, Mode.PEST);
        assertThat(p.others()).extracting(CandidateVO::name).containsExactly("B", "E", "D");
        assertThat(p.spreadFast()).isFalse();
        assertThat(p.urgencyDays()).isNull();
    }

    @Test
    void acceptsNumericStringsForConfidenceAndUrgency() throws Exception {
        String raw = "{\"status\":\"SUCCESS\",\"top_name\":\"番茄早疫病\",\"confidence\":\"0.87\","
                + "\"severity\":\"轻度\",\"spread_fast\":\"false\",\"urgency_days\":\"5天\"}";
        ParsedVision p = VisionResultParser.parse(raw, Mode.PEST);
        assertThat(p.confidence()).isEqualByComparingTo("0.87");
        assertThat(p.urgencyDays()).isEqualTo(5);
    }
}
