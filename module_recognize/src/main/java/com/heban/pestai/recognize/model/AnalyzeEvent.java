package com.heban.pestai.recognize.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.router.RouterPath;

/**
 * S02 一次性事件。
 * <ul>
 *     <li>GO_RESULT：进入 R01，携带 recordId 与 recordJson（Gson 序列化的 RecognitionVO）</li>
 *     <li>GO_ERROR：进入 R02，携带 errorType 与 failReason</li>
 *     <li>CANCELED：已取消识别，返回 S01 并提示</li>
 *     <li>CONFIRM_CANCEL：识别中按返回键，弹出确认对话框</li>
 *     <li>CLOSE：直接关闭（识别完成后返回，或登录过期由壳工程统一跳转登录页）</li>
 * </ul>
 */
public final class AnalyzeEvent {

    public enum Type {
        GO_RESULT,
        GO_ERROR,
        CANCELED,
        CONFIRM_CANCEL,
        CLOSE
    }

    @NonNull
    public final Type type;
    public final long recordId;
    @Nullable
    public final String recordJson;
    @Nullable
    public final String errorType;
    @Nullable
    public final String failReason;

    private AnalyzeEvent(@NonNull Type type, long recordId, @Nullable String recordJson,
                         @Nullable String errorType, @Nullable String failReason) {
        this.type = type;
        this.recordId = recordId;
        this.recordJson = recordJson;
        this.errorType = errorType;
        this.failReason = failReason;
    }

    @NonNull
    public static AnalyzeEvent of(@NonNull Type type) {
        return new AnalyzeEvent(type, RouterPath.NO_RECORD, null, null, null);
    }

    @NonNull
    public static AnalyzeEvent result(long recordId, @NonNull String recordJson) {
        return new AnalyzeEvent(Type.GO_RESULT, recordId, recordJson, null, null);
    }

    @NonNull
    public static AnalyzeEvent error(@NonNull String errorType, @NonNull String failReason) {
        return new AnalyzeEvent(Type.GO_ERROR, RouterPath.NO_RECORD, null, errorType, failReason);
    }
}
