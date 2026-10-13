package com.heban.pestai.record.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.common.constant.FailReason;
import com.heban.pestai.common.constant.Mode;
import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.constant.Severity;
import com.heban.pestai.common.format.RecordTag;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.record.TestRecords;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * HistoryRow 映射与按日期分组（原型 R03 的 6 条示例数据）。
 */
public class HistoryRowBuilderTest {

    private List<HistoryRow> buildSample() {
        return HistoryRowBuilder.build(TestRecords.sample(), TestRecords.TODAY, TestRecords.ZONE,
                HistoryRow.FOOTER_NORMAL, 6);
    }

    @Test
    public void groupsByDayWithHeadersAndFooter() {
        List<HistoryRow> rows = buildSample();
        // 今天(2) + 昨天(2) + 10月08日(2)：3 个标题 + 6 条记录 + 底部提示
        assertEquals(10, rows.size());
        assertHeader(rows.get(0), "今天", "h_2026-10-13", true);
        assertHeader(rows.get(3), "昨天", "h_2026-10-12", false);
        assertHeader(rows.get(6), "10月08日", "h_2026-10-08", false);
        HistoryRow footer = rows.get(9);
        assertEquals(HistoryRow.TYPE_FOOTER, footer.getType());
        assertEquals(HistoryRow.FOOTER_NORMAL, footer.getFooterState());
        assertEquals(6, footer.getTotal());
    }

    @Test
    public void marksFirstAndLastInGroup() {
        List<HistoryRow> rows = buildSample();
        assertTrue(rows.get(1).isFirst());
        assertFalse(rows.get(1).isLast());
        assertFalse(rows.get(2).isFirst());
        assertTrue(rows.get(2).isLast());
    }

    @Test
    public void singleRecordGroupIsFirstAndLast() {
        List<RecordItemVO> one = Collections.singletonList(TestRecords.sample().get(0));
        List<HistoryRow> rows = HistoryRowBuilder.build(one, TestRecords.TODAY, TestRecords.ZONE,
                HistoryRow.FOOTER_LOADING, 1);
        assertEquals(3, rows.size());
        assertTrue(rows.get(1).isFirst());
        assertTrue(rows.get(1).isLast());
        assertEquals(HistoryRow.FOOTER_LOADING, rows.get(2).getFooterState());
    }

    @Test
    public void emptyRecordsProduceNoRows() {
        assertTrue(HistoryRowBuilder.build(Collections.emptyList(), TestRecords.TODAY, TestRecords.ZONE,
                HistoryRow.FOOTER_NORMAL, 0).isEmpty());
    }

    @Test
    public void sampleRowsMatchPrototype() {
        List<HistoryRow> rows = buildSample();
        assertRecord(rows.get(1), HistoryRow.TYPE_RECORD, "黄瓜霜霉病", "病虫害 · 匹配度 92% · 14:20", RecordTag.MODERATE);
        assertRecord(rows.get(2), HistoryRow.TYPE_FAILED, "识别失败", "病虫害 · 网络超时 · 11:02 · 点按重新识别", RecordTag.FAILED);
        assertRecord(rows.get(4), HistoryRow.TYPE_RECORD, "番茄早疫病", "病虫害 · 匹配度 87% · 09:05", RecordTag.MILD);
        assertRecord(rows.get(5), HistoryRow.TYPE_RECORD, "马唐（杂草）", "作物 / 杂草 · 匹配度 90% · 08:40", RecordTag.CROP);
        assertRecord(rows.get(7), HistoryRow.TYPE_RECORD, "桃蚜", "病虫害 · 匹配度 95% · 16:12", RecordTag.SEVERE);
        assertRecord(rows.get(8), HistoryRow.TYPE_RECORD, "辣椒 · 健康植株", "病虫害 · 未发现病虫害 · 15:47", RecordTag.HEALTHY);
        assertEquals("r_1024", rows.get(1).getKey());
        assertEquals(1024, rows.get(1).getRecordId());
        assertFalse(rows.get(2).isImageBad());
        assertEquals("作物", rows.get(5).getTag().text);
    }

    @Test
    public void imageBadRecord() {
        RecordItemVO vo = TestRecords.failed(7, Mode.PEST, RecognitionStatus.IMAGE_BAD, FailReason.IMAGE_BAD,
                TestRecords.at(2026, 10, 13, 10, 15));
        HistoryRow row = HistoryRow.record(vo, true, true, TestRecords.ZONE);
        assertRecord(row, HistoryRow.TYPE_FAILED, "照片不清晰", "病虫害 · 画面模糊 · 10:15 · 点按重新识别", RecordTag.FAILED);
        assertTrue(row.isImageBad());
    }

    @Test
    public void failReasonTexts() {
        assertEquals("病虫害 · 服务暂不可用 · 10:15 · 点按重新识别", failedSubtitle(FailReason.UNAVAILABLE));
        assertEquals("病虫害 · 网络超时 · 10:15 · 点按重新识别", failedSubtitle(FailReason.TIMEOUT));
        assertEquals("病虫害 · 未知原因 · 10:15 · 点按重新识别", failedSubtitle(null));
        assertEquals("病虫害 · 未知原因 · 10:15 · 点按重新识别", failedSubtitle("SOMETHING_NEW"));
    }

    @Test
    public void missingDataFallbacks() {
        RecordItemVO vo = TestRecords.success(8, Mode.PEST, null, null, Severity.MILD,
                TestRecords.at(2026, 10, 13, 9, 0));
        HistoryRow row = HistoryRow.record(vo, true, true, TestRecords.ZONE);
        // top_name 为空显示「未知结果」，confidence 为空省略匹配度段
        assertEquals("未知结果", row.getTitle());
        assertEquals("病虫害 · 09:00", row.getSubtitle());

        RecordItemVO noSeverity = TestRecords.success(9, Mode.PEST, "未知病害", 0.5, null,
                TestRecords.at(2026, 10, 13, 9, 0));
        assertNull(HistoryRow.record(noSeverity, true, true, TestRecords.ZONE).getTag());
    }

    @Test
    public void percentRoundsHalfUp() {
        assertEquals(92, HistoryRow.percent(0.92));
        assertEquals(88, HistoryRow.percent(0.875));
        assertEquals(100, HistoryRow.percent(1.2));
        assertEquals(0, HistoryRow.percent(-0.1));
    }

    @Test
    public void crossYearHeader() {
        RecordItemVO old = TestRecords.success(5, Mode.PEST, "旧记录", 0.8, Severity.MILD,
                TestRecords.at(2025, 12, 30, 8, 0));
        List<HistoryRow> rows = HistoryRowBuilder.build(Collections.singletonList(old), TestRecords.TODAY,
                TestRecords.ZONE, HistoryRow.FOOTER_NORMAL, 1);
        assertEquals("2025年12月30日", rows.get(0).getTitle());
    }

    @Test
    public void equalsComparesDisplayFields() {
        List<HistoryRow> a = buildSample();
        List<HistoryRow> b = buildSample();
        assertEquals(a, b);
        HistoryRow footerChanged = HistoryRow.footer(HistoryRow.FOOTER_NORMAL, 5);
        assertEquals(a.get(9).getKey(), footerChanged.getKey());
        assertNotEquals(a.get(9), footerChanged);
    }

    private String failedSubtitle(String reason) {
        RecordItemVO vo = TestRecords.failed(7, Mode.PEST, RecognitionStatus.FAILED, reason,
                TestRecords.at(2026, 10, 13, 10, 15));
        return HistoryRow.record(vo, true, true, TestRecords.ZONE).getSubtitle();
    }

    private static void assertHeader(HistoryRow row, String title, String key, boolean firstGroup) {
        assertEquals(HistoryRow.TYPE_HEADER, row.getType());
        assertEquals(title, row.getTitle());
        assertEquals(key, row.getKey());
        assertEquals(firstGroup, row.isFirst());
    }

    private static void assertRecord(HistoryRow row, int type, String title, String subtitle, RecordTag tag) {
        assertEquals(type, row.getType());
        assertEquals(title, row.getTitle());
        assertEquals(subtitle, row.getSubtitle());
        assertEquals(tag, row.getTag());
    }
}
