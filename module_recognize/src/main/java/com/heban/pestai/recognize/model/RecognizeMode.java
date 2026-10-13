package com.heban.pestai.recognize.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.router.RouterPath;

/**
 * 识别模式：PEST 病虫害 / CROP 作物与杂草。名称即接口参数 mode 与 RouterPath.EXTRA_MODE 的取值。
 */
public enum RecognizeMode {
    PEST,
    CROP;

    /** Intent 参数或 VO 中的模式字符串 → 枚举；空值与未知取值按 PEST 处理 */
    @NonNull
    public static RecognizeMode of(@Nullable String value) {
        return RouterPath.MODE_CROP.equals(value) ? CROP : PEST;
    }

    /** 接口参数 / Intent 参数取值（PEST / CROP） */
    @NonNull
    public String value() {
        return name();
    }
}
