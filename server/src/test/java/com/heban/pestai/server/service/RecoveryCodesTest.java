package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RecoveryCodesTest {

    private final SecureRandom random = new SecureRandom();

    @Test
    void alphabetHas32CharsWithoutConfusableOnes() {
        assertThat(RecoveryCodes.ALPHABET).isEqualTo("ABCDEFGHJKLMNPQRSTUVWXYZ23456789").hasSize(32);
        assertThat(RecoveryCodes.ALPHABET).doesNotContain("0", "O", "1", "I");
        assertThat(RecoveryCodes.ALPHABET.chars().distinct().count()).isEqualTo(32);
    }

    @Test
    void generatedCodesAre12CharsFromAlphabetAndRandom() {
        Set<String> seen = new HashSet<>();
        Set<Character> usedChars = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String code = RecoveryCodes.generate(random);
            assertThat(code).hasSize(12).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{12}");
            seen.add(code);
            code.chars().forEach(c -> usedChars.add((char) c));
        }
        // 60 位熵：500 次不会重复；字符集中的每个字符都会出现
        assertThat(seen).hasSize(500);
        assertThat(usedChars).hasSize(32);
    }

    @Test
    void formatGroupsByFourWithHyphens() {
        assertThat(RecoveryCodes.format("ABCDEFGHJKLM")).isEqualTo("ABCD-EFGH-JKLM");
        String generated = RecoveryCodes.format(RecoveryCodes.generate(random));
        assertThat(generated).hasSize(14).matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");
    }

    @Test
    void normalizeIgnoresCaseSpacesAndHyphens() {
        assertThat(RecoveryCodes.normalize("ABCD-EFGH-JKLM")).isEqualTo("ABCDEFGHJKLM");
        assertThat(RecoveryCodes.normalize("abcd-efgh-jklm")).isEqualTo("ABCDEFGHJKLM");
        assertThat(RecoveryCodes.normalize("  aBcD efgh  JKLM ")).isEqualTo("ABCDEFGHJKLM");
        assertThat(RecoveryCodes.normalize("abcdefghjklm")).isEqualTo("ABCDEFGHJKLM");
        assertThat(RecoveryCodes.normalize("AB-CD--EF GH-JK\tLM")).isEqualTo("ABCDEFGHJKLM");
        // 输入法常见的全角连字符、破折号、全角空格
        assertThat(RecoveryCodes.normalize("ABCD－EFGH—JKLM")).isEqualTo("ABCDEFGHJKLM");
        assertThat(RecoveryCodes.normalize("ABCD　EFGH　JKLM")).isEqualTo("ABCDEFGHJKLM");
        // 规范形式与显示形式互逆
        String code = RecoveryCodes.generate(random);
        assertThat(RecoveryCodes.normalize(RecoveryCodes.format(code))).isEqualTo(code);
        assertThat(RecoveryCodes.normalize(RecoveryCodes.format(code).toLowerCase())).isEqualTo(code);
    }

    @Test
    void normalizeRejectsWrongLengthOrCharsOutsideAlphabet() {
        assertThat(RecoveryCodes.normalize(null)).isNull();
        assertThat(RecoveryCodes.normalize("")).isNull();
        assertThat(RecoveryCodes.normalize("ABCD-EFGH-JKL")).isNull();       // 11 位
        assertThat(RecoveryCodes.normalize("ABCD-EFGH-JKLMN")).isNull();     // 13 位
        assertThat(RecoveryCodes.normalize("ABCD-EFGH-JKL0")).isNull();      // 数字 0
        assertThat(RecoveryCodes.normalize("ABCD-EFGH-JKLO")).isNull();      // 字母 O
        assertThat(RecoveryCodes.normalize("ABCD-EFGH-JKL1")).isNull();      // 数字 1
        assertThat(RecoveryCodes.normalize("abcd-efgh-jkli")).isNull();      // 字母 i → I
        assertThat(RecoveryCodes.normalize("ABCD_EFGH_JKLM")).isNull();      // 下划线不是分隔符
        assertThat(RecoveryCodes.normalize("ＡBCD-EFGH-JKLM")).isNull();     // 全角字母
    }
}
