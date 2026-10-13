package com.heban.pestai.mine.format;

import static org.junit.Assert.assertEquals;

import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.UserStatsVO;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

public class StatsFormatterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 16, 0);

    @Test
    public void nullStatsShowsPlaceholder() {
        assertEquals("--", StatsFormatter.total(null));
        assertEquals("--", StatsFormatter.pestKinds(null));
        assertEquals("--", StatsFormatter.aiCount(null));
        assertEquals("", StatsFormatter.recordCount(null));
        assertEquals("本月已识别 -- 次", StatsFormatter.month(null));
        assertEquals("最近一次：--", StatsFormatter.lastRecord(null, NOW));
    }

    @Test
    public void sampleStats() {
        UserStatsVO stats = stats(record("黄瓜霜霉病", RecognitionStatus.SUCCESS, LocalDateTime.of(2026, 10, 10, 14, 20)));
        assertEquals("36", StatsFormatter.total(stats));
        assertEquals("12", StatsFormatter.pestKinds(stats));
        assertEquals("18", StatsFormatter.aiCount(stats));
        assertEquals("36 条", StatsFormatter.recordCount(stats));
        assertEquals("本月已识别 9 次", StatsFormatter.month(stats));
        assertEquals("最近一次：黄瓜霜霉病 · 今天 14:20", StatsFormatter.lastRecord(stats, NOW));
    }

    @Test
    public void noRecordYet() {
        UserStatsVO stats = new UserStatsVO();
        assertEquals("0", StatsFormatter.total(stats));
        assertEquals("0 条", StatsFormatter.recordCount(stats));
        assertEquals("本月已识别 0 次", StatsFormatter.month(stats));
        assertEquals("最近一次：暂无", StatsFormatter.lastRecord(stats, NOW));
    }

    @Test
    public void lastRecordFailed() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 10, 14, 20);
        assertEquals("最近一次：识别失败 · 今天 14:20",
                StatsFormatter.lastRecord(stats(record(null, RecognitionStatus.FAILED, time)), NOW));
        assertEquals("最近一次：照片不清晰 · 今天 14:20",
                StatsFormatter.lastRecord(stats(record("", RecognitionStatus.IMAGE_BAD, time)), NOW));
    }

    @Test
    public void lastRecordYesterdayAndWithoutTime() {
        assertEquals("最近一次：番茄早疫病 · 昨天 09:05", StatsFormatter.lastRecord(
                stats(record("番茄早疫病", RecognitionStatus.SUCCESS, LocalDateTime.of(2026, 10, 9, 9, 5))), NOW));
        assertEquals("最近一次：番茄早疫病",
                StatsFormatter.lastRecord(stats(record("番茄早疫病", RecognitionStatus.SUCCESS, null)), NOW));
    }

    private static UserStatsVO stats(RecordItemVO last) {
        UserStatsVO stats = new UserStatsVO();
        stats.setTotal(36);
        stats.setPestKinds(12);
        stats.setAiCount(18);
        stats.setMonthCount(9);
        stats.setLastRecord(last);
        return stats;
    }

    private static RecordItemVO record(String topName, String status, LocalDateTime time) {
        RecordItemVO record = new RecordItemVO();
        record.setId(2036);
        record.setTopName(topName);
        record.setStatus(status);
        if (time != null) {
            record.setCreatedAt(Date.from(time.atZone(ZoneId.systemDefault()).toInstant()));
        }
        return record;
    }
}
