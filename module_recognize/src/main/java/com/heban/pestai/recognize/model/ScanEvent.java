package com.heban.pestai.recognize.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * S01 一次性事件。GO_ANALYZING 携带照片 Uri 与模式。
 */
public final class ScanEvent {

    public enum Type {
        /** 弹出系统相机权限对话框 */
        REQUEST_PERMISSION,
        /** 打开本应用的系统设置页 */
        OPEN_SETTINGS,
        /** 进入 S02 */
        GO_ANALYZING,
        /** 进入 R03 识别记录 */
        GO_HISTORY
    }

    @NonNull
    public final Type type;
    @Nullable
    public final String imageUri;
    @Nullable
    public final RecognizeMode mode;

    private ScanEvent(@NonNull Type type, @Nullable String imageUri, @Nullable RecognizeMode mode) {
        this.type = type;
        this.imageUri = imageUri;
        this.mode = mode;
    }

    @NonNull
    public static ScanEvent of(@NonNull Type type) {
        return new ScanEvent(type, null, null);
    }

    @NonNull
    public static ScanEvent analyzing(@NonNull String imageUri, @NonNull RecognizeMode mode) {
        return new ScanEvent(Type.GO_ANALYZING, imageUri, mode);
    }
}
