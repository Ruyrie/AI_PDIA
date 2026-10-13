package com.heban.pestai.common.constant;

import androidx.annotation.Nullable;

/**
 * 识别状态（公共数据 05「四、公共枚举」）。
 */
public final class RecognitionStatus {

    /** 识别成功 */
    public static final String SUCCESS = "SUCCESS";
    /** 照片不清晰 */
    public static final String IMAGE_BAD = "IMAGE_BAD";
    /** 识别失败 */
    public static final String FAILED = "FAILED";

    private RecognitionStatus() {
    }

    /** 界面文字，未知取值返回 null */
    @Nullable
    public static String text(@Nullable String value) {
        if (value == null) {
            return null;
        }
        switch (value) {
            case SUCCESS:
                return "识别成功";
            case IMAGE_BAD:
                return "照片不清晰";
            case FAILED:
                return "识别失败";
            default:
                return null;
        }
    }
}
