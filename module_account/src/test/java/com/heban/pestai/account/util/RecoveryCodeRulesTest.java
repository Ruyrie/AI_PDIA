package com.heban.pestai.account.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecoveryCodeRulesTest {

    @Test
    public void normalize_upperCasesAndKeepsLettersAndDigitsOnly() {
        assertEquals("ABCDEFGHJKLM", RecoveryCodeRules.normalize("abcd-efgh jklm"));
        assertEquals("ABCD2345", RecoveryCodeRules.normalize(" ab cd—23，45 "));
        assertEquals("", RecoveryCodeRules.normalize(null));
        assertEquals("", RecoveryCodeRules.normalize("- -"));
        // 最多 12 位有效字符
        assertEquals("ABCDEFGHJKLM", RecoveryCodeRules.normalize("ABCD-EFGH-JKLM-NPQR"));
        // 中文等非 ASCII 字母被忽略
        assertEquals("AB", RecoveryCodeRules.normalize("恢复码ａAｂB"));
    }

    @Test
    public void format_insertsHyphenEveryFourChars() {
        assertEquals("", RecoveryCodeRules.format(""));
        assertEquals("A", RecoveryCodeRules.format("a"));
        assertEquals("ABCD", RecoveryCodeRules.format("abcd"));          // 满 4 位末尾不加连字符，便于删除
        assertEquals("ABCD-E", RecoveryCodeRules.format("abcde"));
        assertEquals("ABCD-EFGH", RecoveryCodeRules.format("ABCD-EFGH-"));
        assertEquals("ABCD-EFGH-JKLM", RecoveryCodeRules.format("abcdefghjklm"));
        assertEquals("ABCD-EFGH-JKLM", RecoveryCodeRules.format("ABCD EFGH JKLM NPQR"));   // 粘贴多余内容截断
        assertEquals(RecoveryCodeRules.FORMATTED_LENGTH, RecoveryCodeRules.format("abcdefghjklmnpqr").length());
    }

    @Test
    public void completeAndValid() {
        assertFalse(RecoveryCodeRules.isComplete("ABCD-EFGH-JKL"));
        assertTrue(RecoveryCodeRules.isComplete("abcd efgh jklm"));
        assertTrue(RecoveryCodeRules.isValid("abcd-efgh-jklm"));
        assertTrue(RecoveryCodeRules.isValid("2345-6789-WXYZ"));

        // 字符集去掉了易混的 0 / O / 1 / I
        assertTrue(RecoveryCodeRules.hasInvalidChars("ABCO"));
        assertTrue(RecoveryCodeRules.hasInvalidChars("abc0"));
        assertTrue(RecoveryCodeRules.hasInvalidChars("i"));
        assertTrue(RecoveryCodeRules.hasInvalidChars("1"));
        assertFalse(RecoveryCodeRules.hasInvalidChars("ABCD-EF"));
        assertFalse(RecoveryCodeRules.hasInvalidChars(null));
        assertTrue(RecoveryCodeRules.isComplete("ABCD-EFGH-JKL0"));
        assertFalse(RecoveryCodeRules.isValid("ABCD-EFGH-JKL0"));
    }

    @Test
    public void cursor_staysAfterSameCharacter() {
        // 输入第 5 个字符：「ABCDE|」→「ABCD-E|」
        String typed = "ABCDE";
        int count = RecoveryCodeRules.countCodeChars(typed, 5);
        assertEquals(5, count);
        assertEquals(6, RecoveryCodeRules.cursorAfterChars(RecoveryCodeRules.format(typed), count));

        // 在中间插入：「AB|CD-EFGH」输入 x →「ABx|CD-EFGH」→「ABXC-DEFG-H」，光标在 X 之后
        String inserted = "ABxCD-EFGH";
        count = RecoveryCodeRules.countCodeChars(inserted, 3);
        assertEquals(3, RecoveryCodeRules.cursorAfterChars(RecoveryCodeRules.format(inserted), count));

        // 删除连字符：「ABCD|EFGH」恢复为「ABCD|-EFGH」，光标停在连字符之前
        String deleted = "ABCDEFGH";
        count = RecoveryCodeRules.countCodeChars(deleted, 4);
        assertEquals("ABCD-EFGH", RecoveryCodeRules.format(deleted));
        assertEquals(4, RecoveryCodeRules.cursorAfterChars("ABCD-EFGH", count));

        assertEquals(0, RecoveryCodeRules.cursorAfterChars("ABCD-EF", 0));
        assertEquals(7, RecoveryCodeRules.cursorAfterChars("ABCD-EF", 99));
        assertEquals(0, RecoveryCodeRules.countCodeChars(null, 3));
        assertEquals(4, RecoveryCodeRules.countCodeChars("ab-cd", 99));
    }
}
