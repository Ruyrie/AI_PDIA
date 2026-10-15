package com.heban.pestai.ai.chat;

import androidx.annotation.NonNull;

/**
 * Q02 一次性事件：游客拦截、打开引用的识别结果（R01）、滚到底部、添加图片（系统相机 / 照片选择器）。
 * 提示文案走 BaseViewModel 的 toast。
 */
public final class ChatEvent {

    public static final int NEED_LOGIN = 1;
    public static final int OPEN_RECORD = 2;
    public static final int SCROLL_TO_BOTTOM = 3;
    /** 打开系统照片选择器（最多可选张数由页面向 ViewModel 查询） */
    public static final int PICK_IMAGE = 4;
    /** 申请相机权限后打开系统相机 */
    public static final int TAKE_PHOTO = 5;

    public final int type;
    /** OPEN_RECORD 时为识别记录 ID */
    public final long recordId;

    private ChatEvent(int type, long recordId) {
        this.type = type;
        this.recordId = recordId;
    }

    @NonNull
    static ChatEvent of(int type) {
        return new ChatEvent(type, 0);
    }

    @NonNull
    static ChatEvent openRecord(long recordId) {
        return new ChatEvent(OPEN_RECORD, recordId);
    }
}
