package com.heban.pestai.server.common;

/** 识别模式：PEST 病虫害，CROP 作物 / 杂草。 */
public final class Mode {
    public static final String PEST = "PEST";
    public static final String CROP = "CROP";
    /** 用于 @Pattern 校验 */
    public static final String REGEX = "PEST|CROP";

    private Mode() {
    }

    public static boolean isValid(String mode) {
        return PEST.equals(mode) || CROP.equals(mode);
    }
}
