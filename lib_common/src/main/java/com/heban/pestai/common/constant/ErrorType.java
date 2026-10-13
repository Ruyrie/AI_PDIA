package com.heban.pestai.common.constant;

import androidx.annotation.Nullable;

/**
 * R02 识别异常页的状态（公共数据 05「四、公共枚举」）。
 */
public final class ErrorType {

    /** 照片不清晰（错误码 42201） */
    public static final String IMAGE_BAD = "IMAGE_BAD";
    /** 识别失败（50201 / 50401 / 网络异常） */
    public static final String FAILED = "FAILED";

    private ErrorType() {
    }
}
