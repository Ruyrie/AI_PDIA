package com.heban.pestai.ai.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Q02 AI 气泡顶部「思考区」的状态与文字（纯 Java，便于单元测试）。
 * <table>
 *     <tr><td>{@link #THINKING}</td><td>生成中且正文尚未开始：「正在思考…」+「已用 N 秒」，思考内容默认展开</td></tr>
 *     <tr><td>{@link #DONE}</td><td>正文已开始且有思考内容：折叠为「已思考 N 秒」，可点开</td></tr>
 *     <tr><td>{@link #STOPPED}</td><td>正文未开始就停止 / 出错，但有思考内容：折叠为「已停止思考」，可点开</td></tr>
 *     <tr><td>{@link #HIDDEN}</td><td>没有思考内容（带图提问走识图模型）且不在思考中：整行隐藏</td></tr>
 * </table>
 */
public final class ThinkingFormatter {

    public static final int HIDDEN = 0;
    public static final int THINKING = 1;
    public static final int DONE = 2;
    public static final int STOPPED = 3;

    public static final String TITLE_THINKING = "正在思考…";
    public static final String TITLE_STOPPED = "已停止思考";
    /** 历史消息没有 reasoning_ms 时 */
    public static final String TITLE_DONE_UNKNOWN = "已思考";

    private ThinkingFormatter() {
    }

    /**
     * @param streaming    回答是否生成中
     * @param hasContent   是否已收到正文
     * @param hasReasoning 是否有思考内容
     */
    public static int state(boolean streaming, boolean hasContent, boolean hasReasoning) {
        if (streaming && !hasContent) {
            return THINKING;
        }
        if (!hasReasoning) {
            return HIDDEN;
        }
        return hasContent ? DONE : STOPPED;
    }

    /** 可以点击展开 / 收起 */
    public static boolean isCollapsible(int state) {
        return state == DONE || state == STOPPED;
    }

    /** 标题：正在思考… / 已思考 N 秒 / 已停止思考；HIDDEN 返回空串 */
    @NonNull
    public static String title(int state, @Nullable Long reasoningMs) {
        switch (state) {
            case THINKING:
                return TITLE_THINKING;
            case DONE:
                return reasoningMs == null ? TITLE_DONE_UNKNOWN : "已思考 " + seconds(reasoningMs) + " 秒";
            case STOPPED:
                return TITLE_STOPPED;
            case HIDDEN:
            default:
                return "";
        }
    }

    /** 思考耗时：毫秒四舍五入到秒，至少 1 秒 */
    public static long seconds(long ms) {
        return Math.max(1, Math.round(ms / 1000.0));
    }

    /** 思考中右侧的计时「已用 N 秒」：从发送时刻起的整秒数（向下取整） */
    @NonNull
    public static String elapsed(long elapsedMs) {
        return "已用 " + Math.max(0, elapsedMs / 1000) + " 秒";
    }
}
