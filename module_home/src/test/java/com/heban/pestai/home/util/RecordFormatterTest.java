package com.heban.pestai.home.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.common.constant.ErrorType;
import com.heban.pestai.common.format.RecordTag;
import com.heban.pestai.common.format.TimeFormatter;
import com.heban.pestai.common.vo.RecordItemVO;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 模块04 技术准备文档 2.10：RecordFormatter / RecordTag 与原型示例一致，失败、照片不清晰、作物、健康等特殊行。
 */
public class RecordFormatterTest {

    /** 原型时间：2026-10-10 14:30 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 14, 30);

    static Date date(int year, int month, int day, int hour, int minute) {
        return Date.from(LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant());
    }

    static RecordItemVO success(long id, String mode, String name, Double confidence, String severity, Date createdAt) {
        RecordItemVO item = new RecordItemVO();
        item.setId(id);
        item.setMode(mode);
        item.setStatus("SUCCESS");
        item.setTopName(name);
        item.setConfidence(confidence);
        item.setSeverity(severity);
        item.setImageUrl("http://10.0.2.2:8080/files/rec/20261010/" + id + ".jpg");
        item.setCreatedAt(createdAt);
        return item;
    }

    static RecordItemVO failed(long id, String status, String failReason, Date createdAt) {
        RecordItemVO item = new RecordItemVO();
        item.setId(id);
        item.setMode("PEST");
        item.setStatus(status);
        item.setFailReason(failReason);
        item.setImageUrl("http://10.0.2.2:8080/files/rec/20261010/" + id + ".jpg");
        item.setCreatedAt(createdAt);
        return item;
    }

    @Test
    public void prototypeSamples() {
        RecordItemVO cucumber = success(1024, "PEST", "黄瓜霜霉病", 0.92, "中度", date(2026, 10, 10, 14, 20));
        RecordItemVO tomato = success(1019, "PEST", "番茄早疫病", 0.87, "轻度", date(2026, 10, 9, 9, 5));
        RecordItemVO aphid = success(1003, "PEST", "桃蚜", 0.95, "较重", date(2026, 10, 8, 16, 12));

        assertEquals("黄瓜霜霉病", RecordFormatter.title(cucumber));
        assertEquals("病虫害 · 匹配度 92% · 今天 14:20", RecordFormatter.meta(cucumber, NOW));
        assertEquals(RecordTag.MODERATE, RecordFormatter.tag(cucumber));
        assertEquals("中度", RecordFormatter.tag(cucumber).text);

        assertEquals("番茄早疫病", RecordFormatter.title(tomato));
        assertEquals("病虫害 · 匹配度 87% · 昨天 09:05", RecordFormatter.meta(tomato, NOW));
        assertEquals(RecordTag.MILD, RecordFormatter.tag(tomato));

        assertEquals("桃蚜", RecordFormatter.title(aphid));
        assertEquals("病虫害 · 匹配度 95% · 10月08日", RecordFormatter.meta(aphid, NOW));
        assertEquals(RecordTag.SEVERE, RecordFormatter.tag(aphid));
    }

    @Test
    public void failedRecord() {
        RecordItemVO item = failed(1030, "FAILED", "TIMEOUT", date(2026, 10, 10, 11, 2));
        assertEquals("识别失败", RecordFormatter.title(item));
        assertEquals("病虫害 · 网络超时 · 今天 11:02", RecordFormatter.meta(item, NOW));
        assertEquals(RecordTag.FAILED, RecordFormatter.tag(item));
        assertEquals("失败", RecordFormatter.tag(item).text);
        assertEquals(ErrorType.FAILED, RecordFormatter.errorType(item));

        item.setFailReason("UNAVAILABLE");
        assertEquals("病虫害 · 服务暂不可用 · 今天 11:02", RecordFormatter.meta(item, NOW));
        item.setFailReason("NETWORK");
        assertEquals("病虫害 · 网络异常 · 今天 11:02", RecordFormatter.meta(item, NOW));
        // 原因码为空或未知时省略原因段
        item.setFailReason(null);
        assertEquals("病虫害 · 今天 11:02", RecordFormatter.meta(item, NOW));
        item.setFailReason("SOMETHING_NEW");
        assertEquals("病虫害 · 今天 11:02", RecordFormatter.meta(item, NOW));
    }

    @Test
    public void imageBadRecord() {
        RecordItemVO item = failed(1031, "IMAGE_BAD", "IMAGE_BAD", date(2026, 10, 9, 20, 0));
        assertEquals("照片不清晰", RecordFormatter.title(item));
        assertEquals("病虫害 · 画面模糊 · 昨天 20:00", RecordFormatter.meta(item, NOW));
        assertEquals(RecordTag.FAILED, RecordFormatter.tag(item));
        assertEquals(ErrorType.IMAGE_BAD, RecordFormatter.errorType(item));
    }

    @Test
    public void cropAndHealthyRecords() {
        RecordItemVO crop = success(1020, "CROP", "马唐（杂草）", 0.90, null, date(2026, 10, 9, 8, 40));
        assertEquals("作物 / 杂草 · 匹配度 90% · 昨天 08:40", RecordFormatter.meta(crop, NOW));
        assertEquals(RecordTag.CROP, RecordFormatter.tag(crop));
        assertEquals("作物", RecordFormatter.tag(crop).text);

        RecordItemVO healthy = success(1002, "PEST", "辣椒 · 健康植株", 0.97, "健康", date(2026, 10, 8, 15, 47));
        assertEquals("病虫害 · 未发现病虫害 · 10月08日", RecordFormatter.meta(healthy, NOW));
        assertEquals(RecordTag.HEALTHY, RecordFormatter.tag(healthy));
    }

    @Test
    public void nullFieldsAreOmitted() {
        RecordItemVO item = success(1, "PEST", "黄瓜霜霉病", null, null, date(2025, 12, 30, 9, 0));
        assertEquals("病虫害 · 2025年12月30日", RecordFormatter.meta(item, NOW));
        // severity 为空或不在四档内时不显示标签
        assertNull(RecordFormatter.tag(item));
        item.setSeverity("未知");
        assertNull(RecordFormatter.tag(item));

        item.setCreatedAt(null);
        item.setMode(null);
        assertEquals("", RecordFormatter.meta(item, NOW));
        assertEquals("", RecordFormatter.meta(null, NOW));
        assertNull(RecordFormatter.tag(null));
    }

    @Test
    public void unknownStatusTreatedAsFailed() {
        RecordItemVO item = failed(7, "PENDING", null, date(2026, 10, 10, 9, 0));
        assertEquals("FAILED", RecordFormatter.status(item));
        assertEquals("识别失败", RecordFormatter.title(item));
        assertEquals(RecordTag.FAILED, RecordFormatter.tag(item));
        assertEquals(ErrorType.FAILED, RecordFormatter.errorType(item));

        item.setStatus(null);
        assertEquals("FAILED", RecordFormatter.status(item));
    }

    @Test
    public void percentText() {
        assertEquals("92%", RecordFormatter.percentText(0.92));
        assertEquals("87%", RecordFormatter.percentText(0.87));
        assertEquals("100%", RecordFormatter.percentText(0.999));
        assertEquals("0%", RecordFormatter.percentText(0.0));
        assertNull(RecordFormatter.percentText(null));
    }

    @Test
    public void modeAndFailReasonText() {
        assertEquals("病虫害", RecordFormatter.modeText("PEST"));
        assertEquals("作物 / 杂草", RecordFormatter.modeText("CROP"));
        assertNull(RecordFormatter.modeText(null));
        assertEquals("网络超时", RecordFormatter.failReasonText("TIMEOUT"));
        assertEquals("服务暂不可用", RecordFormatter.failReasonText("UNAVAILABLE"));
        assertEquals("网络异常", RecordFormatter.failReasonText("NETWORK"));
        assertEquals("画面模糊", RecordFormatter.failReasonText("IMAGE_BAD"));
        assertNull(RecordFormatter.failReasonText(null));
        assertNull(RecordFormatter.failReasonText("OTHER"));
    }

    @Test
    public void successWithoutNameHasFallbackTitle() {
        RecordItemVO item = success(3, "PEST", null, 0.5, "轻度", date(2026, 10, 10, 9, 0));
        assertTrue(RecordFormatter.title(item).length() > 0);
    }

    /** TimeFormatter（lib_common）在首页副标题中的用法：跨年、服务器时间略快 */
    @Test
    public void timeFormatterEdges() {
        LocalDateTime newYear = LocalDateTime.of(2027, 1, 1, 10, 0);
        assertEquals("昨天 20:00", TimeFormatter.formatRecent(date(2026, 12, 31, 20, 0), newYear));
        assertEquals("今天 14:33", TimeFormatter.formatRecent(date(2026, 10, 10, 14, 33), NOW));
    }
}
