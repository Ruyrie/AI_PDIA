package com.heban.pestai.recognize.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

public class TimeTextTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 18, 0);

    @Test
    public void today() {
        assertEquals("今天 14:20", TimeText.of(LocalDateTime.of(2026, 10, 13, 14, 20), NOW));
    }

    @Test
    public void yesterday() {
        assertEquals("昨天 09:05", TimeText.of(LocalDateTime.of(2026, 10, 12, 9, 5), NOW));
    }

    @Test
    public void earlierThisYear_showsMonthDayAndTime() {
        assertEquals("10月08日 16:12", TimeText.of(LocalDateTime.of(2026, 10, 8, 16, 12), NOW));
    }

    @Test
    public void previousYear_showsYear() {
        assertEquals("2025年12月31日 23:59", TimeText.of(LocalDateTime.of(2025, 12, 31, 23, 59), NOW));
    }

    @Test
    public void date_andNull() {
        Date date = Date.from(LocalDateTime.of(2026, 10, 13, 14, 20).atZone(ZoneId.systemDefault()).toInstant());
        assertEquals("今天 14:20", TimeText.of(date, NOW));
        assertEquals("", TimeText.of((Date) null, NOW));
    }
}
