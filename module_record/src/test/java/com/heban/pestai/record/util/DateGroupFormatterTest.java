package com.heban.pestai.record.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.heban.pestai.record.TestRecords;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;

public class DateGroupFormatterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @Test
    public void today() {
        assertEquals("今天", DateGroupFormatter.format(LocalDate.of(2026, 10, 13), TODAY));
    }

    @Test
    public void yesterday() {
        assertEquals("昨天", DateGroupFormatter.format(LocalDate.of(2026, 10, 12), TODAY));
    }

    @Test
    public void sameYearUsesMonthDay() {
        assertEquals("10月08日", DateGroupFormatter.format(LocalDate.of(2026, 10, 8), TODAY));
        assertEquals("01月01日", DateGroupFormatter.format(LocalDate.of(2026, 1, 1), TODAY));
    }

    @Test
    public void differentYearUsesFullDate() {
        assertEquals("2025年12月30日", DateGroupFormatter.format(LocalDate.of(2025, 12, 30), TODAY));
    }

    @Test
    public void crossMonth() {
        LocalDate octFirst = LocalDate.of(2026, 10, 1);
        assertEquals("昨天", DateGroupFormatter.format(LocalDate.of(2026, 9, 30), octFirst));
        assertEquals("09月29日", DateGroupFormatter.format(LocalDate.of(2026, 9, 29), octFirst));
    }

    @Test
    public void crossYear() {
        LocalDate newYear = LocalDate.of(2027, 1, 1);
        assertEquals("昨天", DateGroupFormatter.format(LocalDate.of(2026, 12, 31), newYear));
        assertEquals("2026年12月30日", DateGroupFormatter.format(LocalDate.of(2026, 12, 30), newYear));
    }

    @Test
    public void toLocalDateUsesZone() {
        // 2026-10-13 00:30 北京时间 = 2026-10-12 16:30 UTC
        java.util.Date time = TestRecords.at(2026, 10, 13, 0, 30);
        assertEquals(LocalDate.of(2026, 10, 13), DateGroupFormatter.toLocalDate(time, TestRecords.ZONE));
        assertEquals(LocalDate.of(2026, 10, 12), DateGroupFormatter.toLocalDate(time, ZoneId.of("UTC")));
        assertNull(DateGroupFormatter.toLocalDate(null, TestRecords.ZONE));
    }
}
