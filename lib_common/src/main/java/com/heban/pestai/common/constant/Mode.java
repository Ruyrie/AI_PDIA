package com.heban.pestai.common.constant;

import androidx.annotation.Nullable;

/**
 * 识别模式（公共数据 05「四、公共枚举」）。
 */
public final class Mode {

    /** 病虫害 */
    public static final String PEST = "PEST";
    /** 作物 / 杂草 */
    public static final String CROP = "CROP";

    private Mode() {
    }

    /** 界面文字，未知取值返回 null */
    @Nullable
    public static String text(@Nullable String value) {
        if (value == null) {
            return null;
        }
        switch (value) {
            case PEST:
                return "病虫害";
            case CROP:
                return "作物 / 杂草";
            default:
                return null;
        }
    }
}
