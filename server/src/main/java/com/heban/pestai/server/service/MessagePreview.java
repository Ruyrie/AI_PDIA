package com.heban.pestai.server.service;

import java.util.regex.Pattern;

import com.heban.pestai.server.common.Rules;

/**
 * 历史对话列表的 last_message：去掉 Markdown 符号（加粗、斜体、删除线、标题 #、引用 &gt;、无序列表符号、行内代码、
 * 代码块围栏、链接与图片语法、分隔线、表格竖线），换行与连续空白合并为一个空格，最多 60 字（按 Unicode 码点）；
 * 图片消息以「[图片]」开头（计入 60 字）。编号列表的「1.」属于正文，保留。
 */
public final class MessagePreview {

    public static final int MAX_LENGTH = 60;
    public static final String IMAGE_PREFIX = "[图片]";

    private static final Pattern CODE_FENCE = Pattern.compile("(?m)^\\s*(```|~~~).*$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("(?m)^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+");
    private static final Pattern QUOTE = Pattern.compile("(?m)^\\s{0,3}(>\\s?)+");
    private static final Pattern BULLET = Pattern.compile("(?m)^\\s*[-*+]\\s+");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`+([^`]*)`+");
    private static final Pattern BOLD_STAR = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern BOLD_UNDERSCORE = Pattern.compile("__(.+?)__");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");
    private static final Pattern ITALIC_STAR = Pattern.compile("(?<![*\\w])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![*\\w])");
    private static final Pattern TABLE_DIVIDER = Pattern.compile("(?m)^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private MessagePreview() {
    }

    /** content 去掉 Markdown 后为空且没有图片时返回 null */
    public static String of(String content, String imageUrl) {
        boolean image = imageUrl != null && !imageUrl.isBlank();
        String text = stripMarkdown(content);
        if (!image) {
            return text.isEmpty() ? null : Rules.truncate(text, MAX_LENGTH);
        }
        return Rules.truncate(IMAGE_PREFIX + text, MAX_LENGTH);
    }

    /** 去掉 Markdown 符号并合并空白；null 返回空字符串 */
    static String stripMarkdown(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String s = content.replace("\r", "");
        s = CODE_FENCE.matcher(s).replaceAll("");
        s = TABLE_DIVIDER.matcher(s).replaceAll("");
        s = HORIZONTAL_RULE.matcher(s).replaceAll("");
        s = HEADING.matcher(s).replaceAll("");
        s = QUOTE.matcher(s).replaceAll("");
        s = BULLET.matcher(s).replaceAll("");
        s = IMAGE.matcher(s).replaceAll("$1");
        s = LINK.matcher(s).replaceAll("$1");
        s = INLINE_CODE.matcher(s).replaceAll("$1");
        s = BOLD_STAR.matcher(s).replaceAll("$1");
        s = BOLD_UNDERSCORE.matcher(s).replaceAll("$1");
        s = STRIKE.matcher(s).replaceAll("$1");
        s = ITALIC_STAR.matcher(s).replaceAll("$1");
        s = s.replace('|', ' ');
        return WHITESPACE.matcher(s).replaceAll(" ").strip();
    }
}
