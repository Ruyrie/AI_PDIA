package com.heban.pestai.ai.util;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineHeightSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UpdateAppearance;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.base.util.DimenUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 回答的简单格式渲染。后端提示词约定只使用三种格式：
 * <ul>
 *     <li>加粗：{@code **黄瓜霜霉病（中度）**} → Bold + onPrimaryContainer 色；</li>
 *     <li>小标题行：{@code ### 处理建议}，或整行加粗且较短（如 {@code **处理建议**}）→ 16sp Medium；</li>
 *     <li>编号列表：{@code 1. 上午通风排湿}（也兼容「1、」「1)」与「- 」「• 」项目符号）→ 14sp，悬挂缩进。</li>
 * </ul>
 * {@link #parse(String)}、{@link #plainText(String)} 与 {@link #fadeTargets(String, int)} 是纯 Java 逻辑（单元测试覆盖）；
 * {@link #format(String, Style)} 把解析结果转换为 SpannableStringBuilder（逐字输出时只传入已显示的部分），
 * {@link #applyFade(SpannableStringBuilder, String, float[])} 给最新显示的字加淡入透明度（替代原来的竖条光标）。
 * 流式输出中未闭合的 ** 视为加粗开始，避免逐字显示时出现符号闪动。
 */
public final class AnswerFormatter {

    public static final int PARAGRAPH = 0;
    public static final int HEADING = 1;
    public static final int LIST_ITEM = 2;

    private static final String BOLD = "**";
    /** 整行加粗视为小标题的最大长度 */
    private static final int MAX_HEADING_LENGTH = 16;
    /** 列表字号 14sp（正文 16sp） */
    private static final float LIST_SCALE = 14f / 16f;

    private static final Pattern HEADING_MARK = Pattern.compile("^#{1,6}\\s+(.*)$");
    private static final Pattern BOLD_LINE = Pattern.compile("^\\*\\*([^*]+)\\*\\*([:：]?)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d{1,2})(?:[.．](?!\\d)|[、)）])\\s*(.*)$");
    private static final Pattern BULLET = Pattern.compile("^[-*•·]\\s+(.*)$");

    private AnswerFormatter() {
    }

    // ==================== 解析（纯 Java） ====================

    /** 一行内的文字片段 */
    public static final class Segment {
        public final String text;
        public final boolean bold;

        Segment(@NonNull String text, boolean bold) {
            this.text = text;
            this.bold = bold;
        }
    }

    /** 一个段落：普通段落 / 小标题 / 列表项 */
    public static final class Block {
        public final int type;
        /** 列表项的编号（如「1.」「•」），其他为 null */
        @Nullable
        public final String label;
        public final List<Segment> segments;
        /** 与上一段之间是否留 6dp 段落间距（连续列表项之间不留） */
        public final boolean gapBefore;

        Block(int type, @Nullable String label, @NonNull List<Segment> segments, boolean gapBefore) {
            this.type = type;
            this.label = label;
            this.segments = segments;
            this.gapBefore = gapBefore;
        }

        /** 去掉格式符号后的文字（不含列表编号） */
        @NonNull
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Segment s : segments) {
                sb.append(s.text);
            }
            return sb.toString();
        }
    }

    @NonNull
    public static List<Block> parse(@Nullable String answer) {
        List<Block> blocks = new ArrayList<>();
        if (answer == null || answer.isEmpty()) {
            return blocks;
        }
        int prevType = -1;
        for (String raw : splitLines(answer)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            int type;
            String label = null;
            List<Segment> segments;
            Matcher m;
            if ((m = HEADING_MARK.matcher(line)).matches()) {
                type = HEADING;
                segments = single(m.group(1).replace(BOLD, "").trim());
            } else if ((m = BOLD_LINE.matcher(line)).matches() && isHeadingText(m.group(1))) {
                type = HEADING;
                segments = single(m.group(1).trim() + m.group(2));
            } else if ((m = NUMBERED.matcher(line)).matches()) {
                type = LIST_ITEM;
                label = m.group(1) + ".";
                segments = inline(m.group(2));
            } else if ((m = BULLET.matcher(line)).matches()) {
                type = LIST_ITEM;
                label = "•";
                segments = inline(m.group(1));
            } else {
                type = PARAGRAPH;
                segments = inline(line);
            }
            boolean gap = !blocks.isEmpty() && !(type == LIST_ITEM && prevType == LIST_ITEM);
            blocks.add(new Block(type, label, segments, gap));
            prevType = type;
        }
        return blocks;
    }

    /** 解析一行中的 **加粗**；未闭合的 ** 之后全部视为加粗 */
    @NonNull
    static List<Segment> inline(@NonNull String text) {
        List<Segment> out = new ArrayList<>();
        boolean bold = false;
        int pos = 0;
        while (true) {
            int idx = text.indexOf(BOLD, pos);
            if (idx < 0) {
                addSegment(out, text.substring(pos), bold);
                break;
            }
            addSegment(out, text.substring(pos, idx), bold);
            bold = !bold;
            pos = idx + BOLD.length();
        }
        return out;
    }

    /**
     * 复制用的纯文字：去掉 ** 与 # 等格式符号，保留换行与编号，与界面看到的文字一致。
     */
    @NonNull
    public static String plainText(@Nullable String answer) {
        if (answer == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = splitLines(answer);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = HEADING_MARK.matcher(line.trim());
            if (m.matches()) {
                line = m.group(1);
            }
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(line.replace(BOLD, ""));
        }
        return sb.toString().trim();
    }

    private static String[] splitLines(@NonNull String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    private static boolean isHeadingText(@NonNull String text) {
        String t = text.trim();
        if (t.isEmpty() || t.length() > MAX_HEADING_LENGTH) {
            return false;
        }
        char last = t.charAt(t.length() - 1);
        return "。！？.!?；;，,".indexOf(last) < 0;
    }

    private static List<Segment> single(@NonNull String text) {
        return text.isEmpty() ? Collections.emptyList() : Collections.singletonList(new Segment(text, false));
    }

    private static void addSegment(List<Segment> out, String text, boolean bold) {
        if (!text.isEmpty()) {
            out.add(new Segment(text, bold));
        }
    }

    // ==================== 渲染（Android） ====================

    /** 渲染所需的颜色与尺寸，每个列表创建一次 */
    public static final class Style {
        final int boldColor;
        final Typeface medium;
        final int gapPx;
        final int listIndentPx;
        final TextPaint listPaint;

        private Style(int boldColor, Typeface medium, int gapPx, int listIndentPx, TextPaint listPaint) {
            this.boldColor = boldColor;
            this.medium = medium;
            this.gapPx = gapPx;
            this.listIndentPx = listIndentPx;
            this.listPaint = listPaint;
        }

        @NonNull
        public static Style from(@NonNull Context context) {
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                    context.getResources().getDisplayMetrics()));
            return new Style(
                    DimenUtils.themeColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer),
                    Typeface.create(Typeface.DEFAULT, 500, false),
                    DimenUtils.dp(context, 6),
                    DimenUtils.dp(context, 4),
                    paint);
        }
    }

    /** 渲染 Markdown（逐字输出时传入已显示的部分） */
    @NonNull
    public static SpannableStringBuilder format(@Nullable String answer, @NonNull Style style) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        List<Block> blocks = parse(answer);
        for (Block block : blocks) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            int start = sb.length();
            if (block.type == LIST_ITEM) {
                sb.append(block.label).append(' ');
            }
            for (Segment seg : block.segments) {
                int s = sb.length();
                sb.append(seg.text);
                if (seg.bold && block.type != HEADING) {
                    sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(style.boldColor), s, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            int end = sb.length();
            if (end == start) {
                continue;
            }
            if (block.type == HEADING) {
                sb.setSpan(new TypefaceSpan(style.medium), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (block.type == LIST_ITEM) {
                sb.setSpan(new RelativeSizeSpan(LIST_SCALE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int rest = style.listIndentPx + Math.round(style.listPaint.measureText(block.label + " "));
                sb.setSpan(new LeadingMarginSpan.Standard(style.listIndentPx, rest), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (block.gapBefore) {
                sb.setSpan(new GapSpan(style.gapPx), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    // ==================== 新字淡入 ====================

    /**
     * 计算淡入透明度对应到渲染结果中的哪些字（纯 Java）。
     * 原文末尾的格式符号（* #）与空白在渲染结果中不存在：跳过它们，其余字按顺序对应到渲染结果末尾的非空白字。
     *
     * @param raw        已显示的原文（Markdown）
     * @param fadeCount  原文末尾带淡入的字数（与透明度数组等长，最后一个为最新的字）
     * @return 长度为 fadeCount 的数组：第 k 个元素是原文第 (raw.length() - fadeCount + k) 个字对应的
     * 「从渲染结果末尾数起的第几个非空白字」（0 为最后一个），没有对应（格式符号、空白）时为 -1
     */
    @NonNull
    public static int[] fadeTargets(@NonNull String raw, int fadeCount) {
        int n = Math.min(fadeCount, raw.length());
        int[] targets = new int[Math.max(0, fadeCount)];
        Arrays.fill(targets, -1);
        int visible = 0;
        for (int k = fadeCount - 1, i = raw.length() - 1; k >= fadeCount - n; k--, i--) {
            char c = raw.charAt(i);
            if (c == '*' || c == '#' || Character.isWhitespace(c)) {
                continue;
            }
            targets[k] = visible++;
        }
        return targets;
    }

    /**
     * 给最新显示的字加淡入透明度。
     *
     * @param sb     渲染结果（{@link #format} 的返回值，或纯文字）
     * @param raw    已显示的原文
     * @param alphas 原文末尾各字的透明度（{@link TypingPacer#fadeAlphas(long)}）
     */
    public static void applyFade(@NonNull SpannableStringBuilder sb, @NonNull String raw, @NonNull float[] alphas) {
        if (alphas.length == 0 || sb.length() == 0) {
            return;
        }
        int[] targets = fadeTargets(raw, alphas.length);
        // 渲染结果末尾第 j 个非空白字的位置
        int maxTarget = -1;
        for (int t : targets) {
            maxTarget = Math.max(maxTarget, t);
        }
        if (maxTarget < 0) {
            return;
        }
        int[] positions = new int[maxTarget + 1];
        int found = 0;
        for (int i = sb.length() - 1; i >= 0 && found <= maxTarget; i--) {
            if (!Character.isWhitespace(sb.charAt(i))) {
                positions[found++] = i;
            }
        }
        for (int k = 0; k < alphas.length; k++) {
            int t = targets[k];
            if (t < 0 || t >= found || alphas[k] >= 1f) {
                continue;
            }
            int pos = positions[t];
            sb.setSpan(new FadeSpan(alphas[k]), pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** 透明度 Span：在已设置的文字颜色（含加粗的 onPrimaryContainer 色）基础上乘以 alpha，须在其他颜色 Span 之后添加 */
    static final class FadeSpan extends CharacterStyle implements UpdateAppearance {
        private final float alpha;

        FadeSpan(float alpha) {
            this.alpha = Math.max(0f, Math.min(1f, alpha));
        }

        @Override
        public void updateDrawState(@NonNull TextPaint tp) {
            tp.setAlpha(Math.round(tp.getAlpha() * alpha));
        }
    }

    /** 段落间距：只在段落第一行上方增加 gap */
    static final class GapSpan implements LineHeightSpan {
        private final int gap;

        GapSpan(int gap) {
            this.gap = gap;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int lineHeight,
                                 Paint.FontMetricsInt fm) {
            if (text instanceof Spanned && ((Spanned) text).getSpanStart(this) == start) {
                fm.ascent -= gap;
                fm.top -= gap;
            }
        }
    }
}
