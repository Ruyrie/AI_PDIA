package com.heban.pestai.ai.chat;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Q02 用户图片九宫格的排列规则（纯 Java，单位 dp，单元测试覆盖）：
 * <ul>
 *     <li>1 张：单格 180 × 135dp，圆角 16dp；</li>
 *     <li>2–4 张：2 列，每格 96dp 正方形；5–9 张：3 列，每格 80dp 正方形；格子圆角 12dp；</li>
 *     <li>格间距 4dp；最后一行不满时靠行尾排列（消息右对齐，右边缘与文字气泡对齐）。</li>
 * </ul>
 * 超过 9 张只排前 9 张。
 */
final class ImageGridSpec {

    static final int MAX_IMAGES = 9;
    static final int GAP_DP = 4;

    static final int SINGLE_WIDTH_DP = 180;
    static final int SINGLE_HEIGHT_DP = 135;
    static final int SINGLE_CORNER_DP = 16;
    static final int TWO_COLUMN_CELL_DP = 96;
    static final int THREE_COLUMN_CELL_DP = 80;
    static final int GRID_CORNER_DP = 12;

    /** 实际排列的张数（0–9） */
    final int count;
    final int columns;
    final int rows;
    final int cellWidthDp;
    final int cellHeightDp;
    final int cornerDp;

    private ImageGridSpec(int count, int columns, int rows, int cellWidthDp, int cellHeightDp, int cornerDp) {
        this.count = count;
        this.columns = columns;
        this.rows = rows;
        this.cellWidthDp = cellWidthDp;
        this.cellHeightDp = cellHeightDp;
        this.cornerDp = cornerDp;
    }

    @NonNull
    static ImageGridSpec of(int imageCount) {
        int count = Math.max(0, Math.min(imageCount, MAX_IMAGES));
        if (count == 0) {
            return new ImageGridSpec(0, 0, 0, 0, 0, 0);
        }
        if (count == 1) {
            return new ImageGridSpec(1, 1, 1, SINGLE_WIDTH_DP, SINGLE_HEIGHT_DP, SINGLE_CORNER_DP);
        }
        int columns = count <= 4 ? 2 : 3;
        int cell = count <= 4 ? TWO_COLUMN_CELL_DP : THREE_COLUMN_CELL_DP;
        int rows = (count + columns - 1) / columns;
        return new ImageGridSpec(count, columns, rows, cell, cell, GRID_CORNER_DP);
    }

    /** 整个九宫格的宽度 */
    int widthDp() {
        return columns == 0 ? 0 : columns * cellWidthDp + (columns - 1) * GAP_DP;
    }

    /** 整个九宫格的高度 */
    int heightDp() {
        return rows == 0 ? 0 : rows * cellHeightDp + (rows - 1) * GAP_DP;
    }

    /** 第 index 张（从 0 开始）左边距九宫格左边缘的距离（从左到右排列时；从右到左布局由 View 镜像） */
    int cellLeftDp(int index) {
        int row = index / columns;
        int column = index % columns;
        int inRow = row == rows - 1 ? count - row * columns : columns;
        // 最后一行不满：整体移到行尾
        int offset = columns - inRow;
        return (column + offset) * (cellWidthDp + GAP_DP);
    }

    /** 第 index 张上边距九宫格上边缘的距离 */
    int cellTopDp(int index) {
        return (index / columns) * (cellHeightDp + GAP_DP);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ImageGridSpec that = (ImageGridSpec) o;
        return count == that.count && columns == that.columns && rows == that.rows
                && cellWidthDp == that.cellWidthDp && cellHeightDp == that.cellHeightDp
                && cornerDp == that.cornerDp;
    }

    @Override
    public int hashCode() {
        return Objects.hash(count, columns, rows, cellWidthDp, cellHeightDp, cornerDp);
    }
}
