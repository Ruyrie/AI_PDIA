package com.heban.pestai.ai.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.router.RouterPath;

import java.util.Objects;

/**
 * 打开 Q02 的参数：引用的识别记录（record_id）、自动发送的问题（question）与图片（image_uri）、
 * 要打开的历史对话（session_id，module_ai 内部参数）。
 * Intent 键见 {@link ChatActivity#EXTRA_QUESTION}、{@link ChatActivity#EXTRA_SESSION_ID}、
 * RouterPath.EXTRA_RECORD_ID、RouterPath.EXTRA_IMAGE_URI。
 */
public final class ChatArgs {

    /** 不打开历史对话 */
    public static final long NO_SESSION = 0;

    /** 识别记录 ID，普通对话为 RouterPath.NO_RECORD（-1） */
    public final long recordId;
    @Nullable
    public final String question;
    /** 待发送图片的本地 Uri 字符串 */
    @Nullable
    public final String imageUri;
    /** 历史对话 ID（Q03 打开），没有为 {@link #NO_SESSION} */
    public final long sessionId;

    public ChatArgs(long recordId, @Nullable String question, @Nullable String imageUri) {
        this(recordId, question, imageUri, NO_SESSION);
    }

    public ChatArgs(long recordId, @Nullable String question, @Nullable String imageUri, long sessionId) {
        this.recordId = recordId;
        this.question = question;
        this.imageUri = imageUri;
        this.sessionId = sessionId;
    }

    @NonNull
    public static ChatArgs ofRecord(long recordId) {
        return new ChatArgs(recordId, null, null);
    }

    @NonNull
    public static ChatArgs ofQuestion(@Nullable String question, @Nullable String imageUri) {
        return new ChatArgs(RouterPath.NO_RECORD, question, imageUri);
    }

    /** 打开历史对话（GET ai/sessions/{id}），继续提问追加到该会话 */
    @NonNull
    public static ChatArgs ofSession(long sessionId) {
        return new ChatArgs(RouterPath.NO_RECORD, null, null, sessionId);
    }

    public boolean hasSession() {
        return sessionId > 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChatArgs)) {
            return false;
        }
        ChatArgs that = (ChatArgs) o;
        return recordId == that.recordId
                && sessionId == that.sessionId
                && Objects.equals(question, that.question)
                && Objects.equals(imageUri, that.imageUri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recordId, question, imageUri, sessionId);
    }
}
