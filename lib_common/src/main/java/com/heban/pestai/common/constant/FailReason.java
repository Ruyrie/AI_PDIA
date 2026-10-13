package com.heban.pestai.common.constant;

import androidx.annotation.Nullable;

/**
 * 失败原因码（NETWORK 只在 App 本地产生，不入库）（公共数据 05「四、公共枚举」）。
 */
public final class FailReason {

    /** 网络超时 */
    public static final String TIMEOUT = "TIMEOUT";
    /** 服务暂不可用 */
    public static final String UNAVAILABLE = "UNAVAILABLE";
    /** 画面模糊 */
    public static final String IMAGE_BAD = "IMAGE_BAD";
    /** 网络异常 */
    public static final String NETWORK = "NETWORK";

    private FailReason() {
    }

    /** 界面文字，未知取值返回 null */
    @Nullable
    public static String text(@Nullable String value) {
        if (value == null) {
            return null;
        }
        switch (value) {
            case TIMEOUT:
                return "网络超时";
            case UNAVAILABLE:
                return "服务暂不可用";
            case IMAGE_BAD:
                return "画面模糊";
            case NETWORK:
                return "网络异常";
            default:
                return null;
        }
    }
}
