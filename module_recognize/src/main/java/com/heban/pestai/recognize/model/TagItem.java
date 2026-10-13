package com.heban.pestai.recognize.model;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.heban.pestai.common.constant.Mode;
import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.recognize.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * R01 结论卡的特征标签（仅前端使用）：文字 + 样式。
 * 文字来自服务器（类别、部位）时保存在 {@link #text}；固定文案（「传播快」）保存在 {@link #textRes}。
 */
public final class TagItem {

    public enum Style {
        /** 普通标签：secondaryContainer */
        NORMAL(com.google.android.material.R.attr.colorSecondaryContainer,
                com.google.android.material.R.attr.colorOnSecondaryContainer),
        /** 提醒标签「传播快」：errorContainer */
        ALERT(com.google.android.material.R.attr.colorErrorContainer,
                com.google.android.material.R.attr.colorOnErrorContainer),
        /** 作物 / 杂草类别：tertiaryContainer */
        CROP(com.google.android.material.R.attr.colorTertiaryContainer,
                com.google.android.material.R.attr.colorOnTertiaryContainer);

        @AttrRes
        public final int bgAttr;
        @AttrRes
        public final int fgAttr;

        Style(@AttrRes int bgAttr, @AttrRes int fgAttr) {
            this.bgAttr = bgAttr;
            this.fgAttr = fgAttr;
        }
    }

    @Nullable
    public final String text;
    @StringRes
    public final int textRes;
    @NonNull
    public final Style style;

    private TagItem(@Nullable String text, @StringRes int textRes, @NonNull Style style) {
        this.text = text;
        this.textRes = textRes;
        this.style = style;
    }

    @NonNull
    public static TagItem text(@NonNull String text, @NonNull Style style) {
        return new TagItem(text, 0, style);
    }

    @NonNull
    public static TagItem res(@StringRes int textRes, @NonNull Style style) {
        return new TagItem(null, textRes, style);
    }

    @NonNull
    public static List<TagItem> listOf(@Nullable RecognitionVO vo) {
        if (vo == null) {
            return Collections.emptyList();
        }
        return listOf(vo.getMode(), vo.getCategory(), vo.getPart(), vo.isSpreadFast());
    }

    /**
     * 生成规则：category 不为空时加入（CROP 模式用 CROP 样式，否则 NORMAL）；part 不为空时加入（NORMAL）；
     * 病虫害模式且 spreadFast 为 true 时加入「传播快」（ALERT）。
     */
    @NonNull
    public static List<TagItem> listOf(@Nullable String mode, @Nullable String category, @Nullable String part,
                                       boolean spreadFast) {
        boolean crop = Mode.CROP.equals(mode);
        List<TagItem> tags = new ArrayList<>(3);
        if (!isBlank(category)) {
            tags.add(text(category.trim(), crop ? Style.CROP : Style.NORMAL));
        }
        if (!isBlank(part)) {
            tags.add(text(part.trim(), Style.NORMAL));
        }
        if (!crop && spreadFast) {
            tags.add(res(R.string.scan_tag_spread_fast, Style.ALERT));
        }
        return tags;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TagItem tagItem = (TagItem) o;
        return textRes == tagItem.textRes && Objects.equals(text, tagItem.text) && style == tagItem.style;
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, textRes, style);
    }
}
