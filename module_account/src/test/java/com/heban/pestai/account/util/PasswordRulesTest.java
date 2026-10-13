package com.heban.pestai.account.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PasswordRulesTest {

    @Test
    public void level_matchesDocumentExamples() {
        assertEquals(PasswordRules.LEVEL_NONE, PasswordRules.level(""));
        assertEquals(PasswordRules.LEVEL_NONE, PasswordRules.level(null));
        assertEquals(PasswordRules.LEVEL_WEAK, PasswordRules.level("12345678"));     // 弱
        assertEquals(PasswordRules.LEVEL_MEDIUM, PasswordRules.level("abc12345"));   // 中
        assertEquals(PasswordRules.LEVEL_STRONG, PasswordRules.level("Abc12345!"));  // 强
    }

    @Test
    public void level_strongWhenSymbolOrLongEnough() {
        assertEquals(PasswordRules.LEVEL_STRONG, PasswordRules.level("abc1234!"));      // 含特殊字符
        assertEquals(PasswordRules.LEVEL_STRONG, PasswordRules.level("abcdef123456"));  // 不少于 12 位
        assertEquals(PasswordRules.LEVEL_MEDIUM, PasswordRules.level("abcde12345a"));   // 11 位，两种字符
    }

    @Test
    public void level_weakWhenAnyRuleFails() {
        assertEquals(PasswordRules.LEVEL_WEAK, PasswordRules.level("abc123"));          // 太短
        assertEquals(PasswordRules.LEVEL_WEAK, PasswordRules.level("abcdefgh"));        // 没有数字
        assertEquals(PasswordRules.LEVEL_WEAK, PasswordRules.level("Abc123456789012345678")); // 21 位
    }

    @Test
    public void rules_threeChecks() {
        assertTrue(PasswordRules.lengthOk("12345678"));
        assertTrue(PasswordRules.lengthOk("12345678901234567890"));
        assertFalse(PasswordRules.lengthOk("1234567"));
        assertFalse(PasswordRules.lengthOk("123456789012345678901"));
        assertFalse(PasswordRules.lengthOk(null));

        assertTrue(PasswordRules.hasLetter("1a"));
        assertFalse(PasswordRules.hasLetter("12345678"));
        assertFalse(PasswordRules.hasLetter("番茄12345"));    // 中文不算字母
        assertTrue(PasswordRules.hasDigit("a1"));
        assertFalse(PasswordRules.hasDigit("abcdefgh"));

        assertTrue(PasswordRules.mixOk("abc12345"));
        assertFalse(PasswordRules.mixOk("abcdefgh"));
        assertTrue(PasswordRules.isValid("abc12345"));
        assertFalse(PasswordRules.isValid("abc1234"));
    }

    @Test
    public void strengthText() {
        assertEquals("", PasswordRules.strengthText(0));
        assertEquals("强度：弱", PasswordRules.strengthText(1));
        assertEquals("强度：中", PasswordRules.strengthText(2));
        assertEquals("强度：强", PasswordRules.strengthText(3));
    }
}
