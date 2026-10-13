package com.heban.pestai.ai.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Q03 历史对话列表的一行（界面模型）：分组标题 / 对话 / 底部加载提示。
 * equals() 比较全部显示字段，供 DiffUtil 使用；{@link #getKey()} 为稳定 key。
 */
public final class SessionRow {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_SESSION = 1;
    public static final int TYPE_FOOTER = 2;

    /** 底部提示：正在加载… */
    public static final int FOOTER_LOADING = 1;
    /** 底部提示：加载失败，点按重试 */
    public static final int FOOTER_FAILED = 2;

    private final int type;
    @NonNull
    private final String key;
    /** 分组标题，或对话标题 */
    @NonNull
    private final String title;
    /** 最后一条消息预览 */
    @NonNull
    private final String preview;
    /** 右上角时间 */
    @NonNull
    private final String time;
    /** 关联识别记录：标题后显示「识别追问」 */
    private final boolean followUp;
    private final long sessionId;
    /** 对话：组内第一行（上圆角）；分组标题：列表中的第一组 */
    private final boolean first;
    /** 对话：组内最后一行（下圆角） */
    private final boolean last;
    private final int footerState;

    private SessionRow(int type, @NonNull String key, @NonNull String title, @NonNull String preview,
                       @NonNull String time, boolean followUp, long sessionId, boolean first, boolean last,
                       int footerState) {
        this.type = type;
        this.key = key;
        this.title = title;
        this.preview = preview;
        this.time = time;
        this.followUp = followUp;
        this.sessionId = sessionId;
        this.first = first;
        this.last = last;
        this.footerState = footerState;
    }

    @NonNull
    public static SessionRow header(@NonNull String key, @NonNull String title, boolean firstGroup) {
        return new SessionRow(TYPE_HEADER, key, title, "", "", false, 0, firstGroup, false, 0);
    }

    @NonNull
    public static SessionRow session(long sessionId, @NonNull String title, @NonNull String preview,
                                     @NonNull String time, boolean followUp, boolean first, boolean last) {
        return new SessionRow(TYPE_SESSION, "s_" + sessionId, title, preview, time, followUp, sessionId,
                first, last, 0);
    }

    @NonNull
    public static SessionRow footer(int footerState) {
        return new SessionRow(TYPE_FOOTER, "footer", "", "", "", false, 0, false, false, footerState);
    }

    public int getType() {
        return type;
    }

    @NonNull
    public String getKey() {
        return key;
    }

    @NonNull
    public String getTitle() {
        return title;
    }

    @NonNull
    public String getPreview() {
        return preview;
    }

    @NonNull
    public String getTime() {
        return time;
    }

    public boolean isFollowUp() {
        return followUp;
    }

    public long getSessionId() {
        return sessionId;
    }

    public boolean isFirst() {
        return first;
    }

    public boolean isLast() {
        return last;
    }

    public int getFooterState() {
        return footerState;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SessionRow that = (SessionRow) o;
        return type == that.type
                && followUp == that.followUp
                && sessionId == that.sessionId
                && first == that.first
                && last == that.last
                && footerState == that.footerState
                && key.equals(that.key)
                && title.equals(that.title)
                && preview.equals(that.preview)
                && time.equals(that.time);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, key, title, preview, time, followUp, sessionId, first, last, footerState);
    }

    @NonNull
    @Override
    public String toString() {
        return "SessionRow{" + key + ", " + title + ", " + time + ", first=" + first + ", last=" + last + '}';
    }
}
