package com.heban.pestai.ai.chat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 用户图片九宫格的排列规则 */
public class ImageGridSpecTest {

    @Test
    public void none() {
        ImageGridSpec spec = ImageGridSpec.of(0);
        assertEquals(0, spec.count);
        assertEquals(0, spec.widthDp());
        assertEquals(0, spec.heightDp());
    }

    @Test
    public void single_180x135_corner16() {
        ImageGridSpec spec = ImageGridSpec.of(1);
        assertEquals(1, spec.columns);
        assertEquals(180, spec.widthDp());
        assertEquals(135, spec.heightDp());
        assertEquals(16, spec.cornerDp);
        assertEquals(0, spec.cellLeftDp(0));
        assertEquals(0, spec.cellTopDp(0));
    }

    @Test
    public void twoToFour_twoColumnsOf96() {
        ImageGridSpec two = ImageGridSpec.of(2);
        assertEquals(2, two.columns);
        assertEquals(1, two.rows);
        assertEquals(96, two.cellWidthDp);
        assertEquals(96, two.cellHeightDp);
        assertEquals(12, two.cornerDp);
        assertEquals(96 * 2 + 4, two.widthDp());
        assertEquals(96, two.heightDp());
        assertEquals(0, two.cellLeftDp(0));
        assertEquals(100, two.cellLeftDp(1));

        ImageGridSpec three = ImageGridSpec.of(3);
        assertEquals(2, three.rows);
        assertEquals(196, three.heightDp());
        assertEquals("最后一行不满：靠行尾（右侧）", 100, three.cellLeftDp(2));
        assertEquals(100, three.cellTopDp(2));

        ImageGridSpec four = ImageGridSpec.of(4);
        assertEquals(2, four.rows);
        assertEquals(0, four.cellLeftDp(2));
        assertEquals(100, four.cellLeftDp(3));
        assertEquals(196, four.widthDp());
        assertEquals(196, four.heightDp());
    }

    @Test
    public void fiveToNine_threeColumnsOf80() {
        ImageGridSpec five = ImageGridSpec.of(5);
        assertEquals(3, five.columns);
        assertEquals(2, five.rows);
        assertEquals(80, five.cellWidthDp);
        assertEquals(12, five.cornerDp);
        assertEquals(80 * 3 + 4 * 2, five.widthDp());
        assertEquals(80 * 2 + 4, five.heightDp());
        assertEquals(168, five.cellLeftDp(2));
        assertEquals("第二行 2 张靠右：从第 2 列开始", 84, five.cellLeftDp(3));
        assertEquals(168, five.cellLeftDp(4));
        assertEquals(84, five.cellTopDp(3));

        ImageGridSpec seven = ImageGridSpec.of(7);
        assertEquals(3, seven.rows);
        assertEquals("第三行只有 1 张：最右列", 168, seven.cellLeftDp(6));
        assertEquals(168, seven.cellTopDp(6));

        ImageGridSpec nine = ImageGridSpec.of(9);
        assertEquals(3, nine.rows);
        assertEquals(248, nine.widthDp());
        assertEquals(248, nine.heightDp());
        assertEquals(0, nine.cellLeftDp(6));
        assertEquals(168, nine.cellLeftDp(8));
        assertEquals(168, nine.cellTopDp(8));
    }

    @Test
    public void moreThanNine_onlyFirstNine() {
        assertEquals(ImageGridSpec.of(9), ImageGridSpec.of(12));
        assertEquals(9, ImageGridSpec.of(12).count);
    }
}
