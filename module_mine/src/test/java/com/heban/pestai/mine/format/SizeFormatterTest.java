package com.heban.pestai.mine.format;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SizeFormatterTest {

    @Test
    public void documentExamples() {
        assertEquals("512 B", SizeFormatter.format(512));
        assertEquals("2 KB", SizeFormatter.format(2048));
        assertEquals("3.5 MB", SizeFormatter.format(3670016));
        assertEquals("128 MB", SizeFormatter.format(134217728));
    }

    @Test
    public void zeroAndNegative() {
        assertEquals("0 B", SizeFormatter.format(0));
        assertEquals("0 B", SizeFormatter.format(-5));
    }

    @Test
    public void boundaries() {
        assertEquals("1023 B", SizeFormatter.format(1023));
        assertEquals("1 KB", SizeFormatter.format(1024));
        assertEquals("1023 KB", SizeFormatter.format(1023 * 1024));
        // 四舍五入后达到 1024 KB 时按 MB 显示
        assertEquals("1.0 MB", SizeFormatter.format(1024 * 1024 - 100));
        assertEquals("1.0 MB", SizeFormatter.format(1024 * 1024));
        assertEquals("9.9 MB", SizeFormatter.format((long) (9.94 * 1024 * 1024)));
        // 9.96 MB 保留 1 位小数会变成 10.0，按 10 MB 以上取整显示
        assertEquals("10 MB", SizeFormatter.format((long) (9.96 * 1024 * 1024)));
        assertEquals("10 MB", SizeFormatter.format(10L * 1024 * 1024));
        assertEquals("2048 MB", SizeFormatter.format(2048L * 1024 * 1024));
    }
}
