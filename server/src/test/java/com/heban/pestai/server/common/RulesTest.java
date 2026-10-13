package com.heban.pestai.server.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RulesTest {

    @Test
    void passwordNeeds8To20CharsWithLetterAndDigit() {
        assertThat(Rules.isValidPassword("abc12345")).isTrue();
        assertThat(Rules.isValidPassword("A1b2C3d4E5f6G7h8I9j0")).isTrue();

        assertThat(Rules.isValidPassword("abc1234")).isFalse();              // 7 位
        assertThat(Rules.isValidPassword("A1b2C3d4E5f6G7h8I9j0k")).isFalse(); // 21 位
        assertThat(Rules.isValidPassword("abcdefgh")).isFalse();              // 没有数字
        assertThat(Rules.isValidPassword("12345678")).isFalse();              // 没有字母
        assertThat(Rules.isValidPassword(null)).isFalse();
    }

    @Test
    void phoneMustBe11DigitsStartingWith1() {
        assertThat(Rules.isValidPhone("13800005678")).isTrue();
        assertThat(Rules.isValidPhone("23800005678")).isFalse();
        assertThat(Rules.isValidPhone("1380000567")).isFalse();
        assertThat(Rules.isValidPhone("138 0000 5678")).isFalse();
        assertThat(Rules.isValidPhone(null)).isFalse();
    }

    @Test
    void truncateCountsCodePoints() {
        assertThat(Rules.truncate("黄瓜叶子发黄卷边是什么原因？", 4)).isEqualTo("黄瓜叶子");
        assertThat(Rules.truncate("😀😀😀", 2)).isEqualTo("😀😀");
        assertThat(Rules.truncate("短", 20)).isEqualTo("短");
        assertThat(Rules.truncate(null, 3)).isNull();
    }
}
