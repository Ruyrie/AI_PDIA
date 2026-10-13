package com.heban.pestai.home;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.home.util.RecordFormatter;

/**
 * 首页一次性跳转事件（仅前端使用），由 HomeViewModel 经 throttleFirst 防连点后发出，HomeFragment 分发处理。
 */
public final class HomeEvent {

    public enum Type {
        /** 打开 S01，带 mode */
        OPEN_SCAN,
        /** 打开系统照片选择器 */
        PICK_PHOTO,
        /** 打开 R03 识别记录 */
        OPEN_HISTORY,
        /** 打开 R01 识别结果，带 recordId */
        OPEN_RESULT,
        /** 打开 R02 识别异常，带 recordId、errorType、failReason、mode、imageUrl */
        OPEN_ERROR,
        /** 游客：显示「登录后才能使用」对话框 */
        NEED_LOGIN,
        /** 打开 A03 密码登录 */
        GO_LOGIN
    }

    @NonNull
    public final Type type;
    /** OPEN_SCAN、OPEN_ERROR：识别模式 PEST / CROP */
    @Nullable
    public final String mode;
    /** OPEN_RESULT、OPEN_ERROR：记录 ID，其他为 RouterPath.NO_RECORD */
    public final long recordId;
    /** OPEN_ERROR：IMAGE_BAD / FAILED */
    @Nullable
    public final String errorType;
    /** OPEN_ERROR：失败原因码，可空 */
    @Nullable
    public final String failReason;
    /** OPEN_ERROR：服务器上的照片地址，可空 */
    @Nullable
    public final String imageUrl;

    private HomeEvent(@NonNull Type type, @Nullable String mode, long recordId, @Nullable String errorType,
                      @Nullable String failReason, @Nullable String imageUrl) {
        this.type = type;
        this.mode = mode;
        this.recordId = recordId;
        this.errorType = errorType;
        this.failReason = failReason;
        this.imageUrl = imageUrl;
    }

    @NonNull
    public static HomeEvent of(@NonNull Type type) {
        return new HomeEvent(type, null, RouterPath.NO_RECORD, null, null, null);
    }

    @NonNull
    public static HomeEvent openScan(@NonNull String mode) {
        return new HomeEvent(Type.OPEN_SCAN, mode, RouterPath.NO_RECORD, null, null, null);
    }

    @NonNull
    public static HomeEvent openResult(long recordId) {
        return new HomeEvent(Type.OPEN_RESULT, null, recordId, null, null, null);
    }

    /** 失败记录：携带 id、error_type（由 status 得到，未知取值按 FAILED）、fail_reason、mode、image_url */
    @NonNull
    public static HomeEvent openError(@NonNull RecordItemVO item) {
        return new HomeEvent(Type.OPEN_ERROR, item.getMode(), item.getId(), RecordFormatter.errorType(item),
                item.getFailReason(), item.getImageUrl());
    }

    @NonNull
    @Override
    public String toString() {
        return "HomeEvent{" + type + ", mode=" + mode + ", recordId=" + recordId + ", errorType=" + errorType
                + ", failReason=" + failReason + '}';
    }
}
