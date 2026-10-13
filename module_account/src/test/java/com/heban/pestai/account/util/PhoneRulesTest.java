package com.heban.pestai.account.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PhoneRulesTest {

    @Test
    public void isValid_elevenDigitsStartingWithOne() {
        assertTrue(PhoneRules.isValid("13800005678"));
        assertTrue(PhoneRules.isValid("19912345678"));
    }

    @Test
    public void isValid_allowsSpaceSeparatedInput() {
        assertTrue(PhoneRules.isValid("138 0000 5678"));
        assertTrue(PhoneRules.isValid(" 138-0000-5678 "));
    }

    @Test
    public void isValid_rejectsWrongLengthLettersAndEmpty() {
        assertFalse(PhoneRules.isValid("1380000567"));      // 10 位
        assertFalse(PhoneRules.isValid("138000056789"));    // 12 位
        assertFalse(PhoneRules.isValid("23800005678"));     // 首位不是 1
        assertFalse(PhoneRules.isValid("1380000567a"));     // 含字母
        assertFalse(PhoneRules.isValid(""));
        assertFalse(PhoneRules.isValid(null));
    }

    @Test
    public void isDefinitelyInvalid_onlyWhenCertain() {
        assertFalse(PhoneRules.isDefinitelyInvalid(""));
        assertFalse(PhoneRules.isDefinitelyInvalid("138"));          // 还在输入
        assertFalse(PhoneRules.isDefinitelyInvalid("138 0000 5678"));
        assertTrue(PhoneRules.isDefinitelyInvalid("2"));             // 首位不是 1
        assertTrue(PhoneRules.isDefinitelyInvalid("13a"));
    }

    @Test
    public void mask_keepsFirstThreeAndLastFour() {
        assertEquals("138****5678", PhoneRules.mask("13800005678"));
        assertEquals("139****1111", PhoneRules.mask("139 0000 1111"));
        assertEquals("12345", PhoneRules.mask("12345"));
    }

    @Test
    public void format_groupsThreeFourFour() {
        assertEquals("138 0000 5678", PhoneRules.format("13800005678"));
        assertEquals("138 0", PhoneRules.format("1380"));
        assertEquals("138", PhoneRules.format("138"));
        assertEquals("138 0000 5678", PhoneRules.format("138000056789"));   // 超出 11 位截断
        assertEquals("138 0000 5678", PhoneRules.format("138 0000 5678"));
    }

    @Test
    public void cursorAfterDigits_findsPositionInFormattedText() {
        assertEquals(0, PhoneRules.cursorAfterDigits("138 0000 5678", 0));
        assertEquals(3, PhoneRules.cursorAfterDigits("138 0000 5678", 3));
        assertEquals(5, PhoneRules.cursorAfterDigits("138 0000 5678", 4));
        assertEquals(13, PhoneRules.cursorAfterDigits("138 0000 5678", 11));
        assertEquals(13, PhoneRules.cursorAfterDigits("138 0000 5678", 20));
    }

    @Test
    public void same_comparesNormalizedNumbers() {
        assertTrue(PhoneRules.same("138 0000 5678", "13800005678"));
        assertFalse(PhoneRules.same("13800005678", "13900001111"));
        assertFalse(PhoneRules.same("", ""));
    }

    @Test
    public void isSmsCode_requiresSixDigits() {
        assertTrue(PhoneRules.isSmsCode("048215"));
        assertFalse(PhoneRules.isSmsCode("04821"));
        assertFalse(PhoneRules.isSmsCode("04821a"));
        assertFalse(PhoneRules.isSmsCode(null));
    }
}
