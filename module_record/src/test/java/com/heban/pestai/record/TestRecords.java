package com.heban.pestai.record;

import com.heban.pestai.common.constant.FailReason;
import com.heban.pestai.common.constant.Mode;
import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.constant.Severity;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.network.PageVO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 单元测试共用的示例数据：与原型 / 公共数据 05 一致的 6 条识别记录（今天 2026-10-13）。
 */
public final class TestRecords {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    public static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 15, 0);

    private TestRecords() {
    }

    public static Date at(int year, int month, int day, int hour, int minute) {
        return Date.from(LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant());
    }

    public static RecordItemVO success(long id, String mode, String name, Double confidence, String severity, Date time) {
        RecordItemVO vo = new RecordItemVO();
        vo.setId(id);
        vo.setMode(mode);
        vo.setImageUrl("http://10.0.2.2:8080/files/rec/" + id + ".jpg");
        vo.setStatus(RecognitionStatus.SUCCESS);
        vo.setTopName(name);
        vo.setConfidence(confidence);
        vo.setSeverity(severity);
        vo.setCreatedAt(time);
        return vo;
    }

    public static RecordItemVO failed(long id, String mode, String status, String failReason, Date time) {
        RecordItemVO vo = new RecordItemVO();
        vo.setId(id);
        vo.setMode(mode);
        vo.setImageUrl("http://10.0.2.2:8080/files/rec/" + id + ".jpg");
        vo.setStatus(status);
        vo.setFailReason(failReason);
        vo.setCreatedAt(time);
        return vo;
    }

    /** 原型 R03 的 6 条示例记录（按 created_at 倒序） */
    public static List<RecordItemVO> sample() {
        return new ArrayList<>(Arrays.asList(
                success(1024, Mode.PEST, "黄瓜霜霉病", 0.92, Severity.MODERATE, at(2026, 10, 13, 14, 20)),
                failed(1023, Mode.PEST, RecognitionStatus.FAILED, FailReason.TIMEOUT, at(2026, 10, 13, 11, 2)),
                success(1022, Mode.PEST, "番茄早疫病", 0.87, Severity.MILD, at(2026, 10, 12, 9, 5)),
                success(1021, Mode.CROP, "马唐（杂草）", 0.90, null, at(2026, 10, 12, 8, 40)),
                success(1020, Mode.PEST, "桃蚜", 0.95, Severity.SEVERE, at(2026, 10, 8, 16, 12)),
                success(1019, Mode.PEST, "辣椒 · 健康植株", 0.97, Severity.HEALTHY, at(2026, 10, 8, 15, 47))));
    }

    /**
     * id 从 fromId 递减的 count 条成功记录，时间按 id 递减（每条间隔 10 分钟，都在今天之前的若干天内）。
     */
    public static List<RecordItemVO> range(long fromId, int count) {
        List<RecordItemVO> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long id = fromId - i;
            Date time = Date.from(NOW.minusMinutes(10 * (1000 - id)).atZone(ZONE).toInstant());
            list.add(success(id, Mode.PEST, "记录" + id, 0.9, Severity.MILD, time));
        }
        return list;
    }

    public static PageVO<RecordItemVO> page(List<RecordItemVO> list, int total, boolean hasMore) {
        PageVO<RecordItemVO> page = new PageVO<>();
        page.setList(new ArrayList<>(list));
        page.setTotal(total);
        page.setHasMore(hasMore);
        return page;
    }
}
