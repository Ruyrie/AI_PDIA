package com.heban.pestai.record.ui;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.BindingAdapter;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.heban.pestai.common.format.RecordTag;
import com.heban.pestai.record.R;

/**
 * module_record 的 BindingAdapter，属性名统一加 record 前缀，避免与 lib_base 及其他组件重名。
 */
public final class RecordBindingAdapters {

    private RecordBindingAdapters() {
    }

    /** 空状态 / 错误态标题（不用嵌套三元表达式，原因见 {@link #setFooterText}） */
    @BindingAdapter(value = {"recordStateTitleError", "recordStateTitleFiltered"})
    public static void setStateTitle(@NonNull TextView view, boolean error, boolean filtered) {
        view.setText(error ? R.string.record_error_title
                : filtered ? R.string.record_empty_filtered_title : R.string.record_empty_title);
    }

    /** 空状态 / 错误态说明 */
    @BindingAdapter(value = {"recordStateDescError", "recordStateDescErrorText",
            "recordStateDescFiltered", "recordStateDescFilterLabel"}, requireAll = false)
    public static void setStateDesc(@NonNull TextView view, boolean error, @Nullable String errorText,
                                    boolean filtered, @Nullable String filterLabel) {
        if (error) {
            view.setText(errorText);
        } else if (filtered) {
            view.setText(view.getResources().getString(R.string.record_empty_filtered_desc,
                    filterLabel == null ? "" : filterLabel));
        } else {
            view.setText(R.string.record_empty_desc);
        }
    }

    /**
     * 底部提示文字。不在布局里写嵌套三元表达式：DataBinding 为嵌套三元生成的脏标记顺序有误，
     * 首次绑定时内层结果不会计算，文字为空。
     */
    @BindingAdapter("recordFooterText")
    public static void setFooterText(@NonNull TextView view, @Nullable HistoryRow row) {
        if (row == null) {
            view.setText(null);
            return;
        }
        switch (row.getFooterState()) {
            case HistoryRow.FOOTER_LOADING:
                view.setText(R.string.record_footer_loading);
                break;
            case HistoryRow.FOOTER_FAILED:
                view.setText(R.string.record_footer_failed);
                break;
            case HistoryRow.FOOTER_NORMAL:
            default:
                view.setText(view.getResources().getString(R.string.record_footer_hint, row.getTotal()));
                break;
        }
    }

    /**
     * 48dp 缩略图：Glide 以 ImageView 所在页面为生命周期宿主，centerCrop + 圆角 12dp；
     * 加载中、image_url 为空或加载失败都显示 image 图标占位（ImageView 本身也按圆角背景裁切）。
     */
    @SuppressWarnings("unchecked")
    @BindingAdapter("recordThumb")
    public static void loadThumb(@NonNull ImageView view, @Nullable String url) {
        if (TextUtils.isEmpty(url)) {
            Glide.with(view).clear(view);
            view.setImageResource(R.drawable.record_thumb_placeholder);
            return;
        }
        int radius = view.getResources().getDimensionPixelSize(R.dimen.record_thumb_radius);
        Glide.with(view)
                .load(url)
                .placeholder(R.drawable.record_thumb_placeholder)
                .error(R.drawable.record_thumb_placeholder)
                .transform(new CenterCrop(), new RoundedCorners(radius))
                .into(view);
    }

    /** 严重程度标签：文字与底色 / 文字色由 RecordTag 决定，null 时隐藏 */
    @BindingAdapter("recordTag")
    public static void bindRecordTag(@NonNull TextView view, @Nullable RecordTag tag) {
        if (tag == null) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setText(tag.text);
        view.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(view, tag.bgAttr)));
        view.setTextColor(MaterialColors.getColor(view, tag.fgAttr));
    }

    /** 失败记录缩略图位置的图标：照片不清晰 blur_on，识别失败 cloud_off */
    @BindingAdapter("recordFailIcon")
    public static void bindFailIcon(@NonNull ImageView view, boolean imageBad) {
        view.setImageResource(imageBad
                ? com.heban.pestai.base.R.drawable.ic_blur_on
                : com.heban.pestai.base.R.drawable.ic_cloud_off);
    }

    /** 筛选 Chip 的选中状态单向绑定 HistoryViewModel.filter（旋转屏幕后自动恢复） */
    @BindingAdapter("recordFilter")
    public static void bindFilter(@NonNull ChipGroup group, @Nullable HistoryFilter filter) {
        if (filter == null) {
            return;
        }
        int id = chipIdOf(filter);
        if (group.getCheckedChipId() != id) {
            group.check(id);
        }
    }

    /**
     * 空状态 / 错误态插图：空状态为 surfaceContainerHigh 底 + image_search（outline 色）；
     * 错误态为 errorContainer 底 + cloud_off（onErrorContainer 色）。
     */
    @BindingAdapter("recordStateError")
    public static void bindStateIcon(@NonNull ImageView view, boolean error) {
        view.setImageResource(error
                ? com.heban.pestai.base.R.drawable.ic_cloud_off
                : com.heban.pestai.base.R.drawable.ic_image_search);
        int background = MaterialColors.getColor(view, error
                ? com.google.android.material.R.attr.colorErrorContainer
                : com.google.android.material.R.attr.colorSurfaceContainerHigh);
        int foreground = MaterialColors.getColor(view, error
                ? com.google.android.material.R.attr.colorOnErrorContainer
                : com.google.android.material.R.attr.colorOutline);
        view.setBackgroundTintList(ColorStateList.valueOf(background));
        view.setImageTintList(ColorStateList.valueOf(foreground));
    }

    /** 空状态按钮图标：错误态「重试」用 refresh，其余「去拍照识别」用 photo_camera */
    @BindingAdapter("recordStateButtonError")
    public static void bindStateButtonIcon(@NonNull MaterialButton button, boolean error) {
        button.setIconResource(error
                ? com.heban.pestai.base.R.drawable.ic_refresh
                : com.heban.pestai.base.R.drawable.ic_photo_camera);
    }

    @IdRes
    static int chipIdOf(@NonNull HistoryFilter filter) {
        switch (filter) {
            case PEST:
                return R.id.history_chip_pest;
            case CROP:
                return R.id.history_chip_crop;
            case ALL:
            default:
                return R.id.history_chip_all;
        }
    }

    @Nullable
    static HistoryFilter filterOfChip(@IdRes int chipId) {
        if (chipId == R.id.history_chip_all) {
            return HistoryFilter.ALL;
        }
        if (chipId == R.id.history_chip_pest) {
            return HistoryFilter.PEST;
        }
        if (chipId == R.id.history_chip_crop) {
            return HistoryFilter.CROP;
        }
        return null;
    }
}
