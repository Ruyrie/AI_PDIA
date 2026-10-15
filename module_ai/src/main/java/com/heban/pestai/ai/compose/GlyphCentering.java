package com.heban.pestai.ai.compose;

/**
 * 单行输入框的文字垂直居中（纯计算，便于单元测试）。
 * <p>输入框按字体的 ascent / descent（行框）居中，但不同机型的系统字体（厂商字体、回退的中文字体）上下留白不对称，
 * 字形实际画在行框中偏上或偏下的位置，看起来就和两侧图标不在同一水平线。这里按这台设备上中文字形的实际边界
 * （Paint.getTextBounds）算出字形中心与行框中心的差，交给 {@link ComposerBar} 调整上下内边距（总高度不变）。</p>
 */
final class GlyphCentering {

    /** 用于测量字形边界的样例文字（输入框的提示与问题多为中文） */
    static final String SAMPLE = "描述作物问题";

    private GlyphCentering() {
    }

    /**
     * 文字需要下移的像素（负数为上移），绝对值不超过 maxShift。
     *
     * @param lineTop     第一行行框顶部（Layout.getLineTop(0)）
     * @param lineBottom  第一行行框底部（Layout.getLineBottom(0)）
     * @param baseline    第一行基线（Layout.getLineBaseline(0)），与 lineTop 同一坐标系
     * @param glyphTop    字形边界顶部，相对基线（getTextBounds 的 top，通常为负）
     * @param glyphBottom 字形边界底部，相对基线（getTextBounds 的 bottom）
     * @param maxShift    最多移动的像素（不能超过原有的上下内边距）
     */
    static int shift(int lineTop, int lineBottom, int baseline, int glyphTop, int glyphBottom, int maxShift) {
        if (lineBottom <= lineTop || glyphBottom <= glyphTop || maxShift <= 0) {
            return 0;
        }
        float lineCenter = (lineBottom - lineTop) / 2f;
        float glyphCenter = (baseline - lineTop) + (glyphTop + glyphBottom) / 2f;
        int shift = Math.round(lineCenter - glyphCenter);
        return Math.max(-maxShift, Math.min(maxShift, shift));
    }
}
