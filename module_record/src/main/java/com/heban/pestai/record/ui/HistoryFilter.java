package com.heban.pestai.record.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.constant.Mode;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.vo.RecordItemVO;

/**
 * 识别记录筛选：与 t_recognition.mode 一一对应。作物识别与杂草识别同属 CROP 模式，因此只有 3 个筛选。
 */
public enum HistoryFilter {

    /** 全部：不传 mode */
    ALL(null, "全部"),
    /** 病虫害：mode = PEST */
    PEST(Mode.PEST, "病虫害"),
    /** 作物 / 杂草：mode = CROP */
    CROP(Mode.CROP, "作物 / 杂草");

    /** 请求参数 mode，「全部」为 null */
    @Nullable
    public final String mode;
    /** 界面文字（空状态「还没有「病虫害」类的识别记录」） */
    @NonNull
    public final String label;

    HistoryFilter(@Nullable String mode, @NonNull String label) {
        this.mode = mode;
        this.label = label;
    }

    @NonNull
    public String getLabel() {
        return label;
    }

    /** 入口参数 EXTRA_MODE → 筛选：PEST → PEST，CROP → CROP，空或其他值 → ALL */
    @NonNull
    public static HistoryFilter fromMode(@Nullable String mode) {
        if (Mode.PEST.equals(mode)) {
            return PEST;
        }
        if (Mode.CROP.equals(mode)) {
            return CROP;
        }
        return ALL;
    }

    /** 进入 S01 时预选的模式：「作物 / 杂草」→ CROP，其余 → PEST */
    @NonNull
    public String scanMode() {
        return this == CROP ? RouterPath.MODE_CROP : RouterPath.MODE_PEST;
    }

    /** 记录是否属于该筛选 */
    public boolean accepts(@NonNull RecordItemVO item) {
        return mode == null || mode.equals(item.getMode());
    }
}
