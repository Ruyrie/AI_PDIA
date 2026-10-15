package com.heban.pestai.ai.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.router.RouterPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 打开 Q02 的参数：引用的识别记录（record_id）、自动发送的问题（question）与图片、
 * 要打开的历史对话（session_id，module_ai 内部参数）。
 * <p>图片由 Q01 选好并<b>已上传</b>：{@link #imageUrls} 是 files/image 返回的地址（Q02 直接发送，不再上传），
 * {@link #imageUris} 是一一对应的本地 Uri（只用于显示，避免重新加载）。两者长度不一致时（Intent 被篡改等）只保留地址。</p>
 * Intent 键见 {@link ChatActivity#EXTRA_QUESTION}、{@link ChatActivity#EXTRA_IMAGE_URIS}、
 * {@link ChatActivity#EXTRA_IMAGE_URLS}、{@link ChatActivity#EXTRA_SESSION_ID}、RouterPath.EXTRA_RECORD_ID。
 */
public final class ChatArgs {

    /** 不打开历史对话 */
    public static final long NO_SESSION = 0;

    /** 识别记录 ID，普通对话为 RouterPath.NO_RECORD（-1） */
    public final long recordId;
    @Nullable
    public final String question;
    /** 自动发送图片的本地 Uri 字符串（与 imageUrls 一一对应，或为空列表），不可修改 */
    @NonNull
    public final List<String> imageUris;
    /** 自动发送图片的服务器地址（已上传，按顺序），没有图片为空列表，不可修改 */
    @NonNull
    public final List<String> imageUrls;
    /** 历史对话 ID（Q03 打开），没有为 {@link #NO_SESSION} */
    public final long sessionId;

    public ChatArgs(long recordId, @Nullable String question, @Nullable List<String> imageUris,
                    @Nullable List<String> imageUrls, long sessionId) {
        this.recordId = recordId;
        this.question = question;
        this.imageUrls = immutable(imageUrls);
        List<String> uris = immutable(imageUris);
        this.imageUris = uris.size() == this.imageUrls.size() ? uris : Collections.emptyList();
        this.sessionId = sessionId;
    }

    @NonNull
    public static ChatArgs ofRecord(long recordId) {
        return new ChatArgs(recordId, null, null, null, NO_SESSION);
    }

    /** 只有文字的问题 */
    @NonNull
    public static ChatArgs ofQuestion(@Nullable String question) {
        return new ChatArgs(RouterPath.NO_RECORD, question, null, null, NO_SESSION);
    }

    /** 文字 + 已上传的图片（Q01 发送） */
    @NonNull
    public static ChatArgs ofQuestion(@Nullable String question, @Nullable List<String> imageUris,
                                      @Nullable List<String> imageUrls) {
        return new ChatArgs(RouterPath.NO_RECORD, question, imageUris, imageUrls, NO_SESSION);
    }

    /** 打开历史对话（GET ai/sessions/{id}），继续提问追加到该会话 */
    @NonNull
    public static ChatArgs ofSession(long sessionId) {
        return new ChatArgs(RouterPath.NO_RECORD, null, null, null, sessionId);
    }

    public boolean hasSession() {
        return sessionId > 0;
    }

    public boolean hasImages() {
        return !imageUrls.isEmpty();
    }

    @NonNull
    private static List<String> immutable(@Nullable List<String> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(list));
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
                && imageUris.equals(that.imageUris)
                && imageUrls.equals(that.imageUrls);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recordId, question, imageUris, imageUrls, sessionId);
    }
}
