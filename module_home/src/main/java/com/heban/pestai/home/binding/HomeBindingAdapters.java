package com.heban.pestai.home.binding;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.databinding.BindingAdapter;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.google.android.material.color.MaterialColors;
import com.heban.pestai.base.util.DimenUtils;
import com.heban.pestai.common.constant.RecognitionStatus;
import com.heban.pestai.common.format.RecordTag;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.home.util.RecordFormatter;

import java.time.LocalDateTime;

/**
 * 首页最近识别行的绑定属性（home 前缀，避免与 lib_base 及其他组件重名）：
 * homeRecordTitle / homeRecordMeta / homeRecordTag / homeRecordIcon / homeRecordThumb。
 * 布局只声明一个 RecordItemVO 变量，显示文字在这里由 RecordFormatter 生成。
 */
public final class HomeBindingAdapters {

    /** 缩略图圆角（dp） */
    private static final int THUMB_RADIUS_DP = 12;

    private HomeBindingAdapters() {
    }

    /** 名称：SUCCESS 为 top_name（onSurface），识别失败 / 照片不清晰为 error 色 */
    @BindingAdapter("homeRecordTitle")
    public static void setRecordTitle(TextView view, @Nullable RecordItemVO item) {
        view.setText(item == null ? "" : RecordFormatter.title(item));
        int colorAttr = item == null || RecordFormatter.isSuccess(item)
                ? com.google.android.material.R.attr.colorOnSurface
                : androidx.appcompat.R.attr.colorError;
        view.setTextColor(MaterialColors.getColor(view, colorAttr));
    }

    /** 副标题：「模式 · 匹配度 / 失败原因 · 时间」，时间按当前日期显示今天 / 昨天 */
    @BindingAdapter("homeRecordMeta")
    public static void setRecordMeta(TextView view, @Nullable RecordItemVO item) {
        view.setText(RecordFormatter.meta(item, LocalDateTime.now()));
    }

    /** 标签：RecordTag 文字 + 主题配色；为 null 时隐藏 */
    @BindingAdapter("homeRecordTag")
    public static void setRecordTag(TextView view, @Nullable RecordItemVO item) {
        RecordTag tag = RecordFormatter.tag(item);
        if (tag == null) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setText(tag.text);
        view.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(view, tag.bgAttr)));
        view.setTextColor(MaterialColors.getColor(view, tag.fgAttr));
    }

    /** 缩略图底层图标：SUCCESS 为 image 占位（outline 色），FAILED 为 cloud_off，IMAGE_BAD 为 blur_on */
    @BindingAdapter("homeRecordIcon")
    public static void setRecordIcon(ImageView view, @Nullable RecordItemVO item) {
        String status = RecordFormatter.status(item);
        int icon;
        int tintAttr;
        if (RecognitionStatus.SUCCESS.equals(status)) {
            icon = com.heban.pestai.base.R.drawable.ic_image;
            tintAttr = com.google.android.material.R.attr.colorOutline;
        } else if (RecognitionStatus.IMAGE_BAD.equals(status)) {
            icon = com.heban.pestai.base.R.drawable.ic_blur_on;
            tintAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
        } else {
            icon = com.heban.pestai.base.R.drawable.ic_cloud_off;
            tintAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
        }
        view.setImageResource(icon);
        view.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(view, tintAttr)));
    }

    /**
     * 缩略图照片：只有 SUCCESS 且 image_url 不为空时用 Glide 加载（centerCrop + 12dp 圆角）；
     * 其他情况先清除该 ImageView 上的旧请求，避免行复用时缩略图错位。加载前与失败时露出底层占位图标。
     */
    @BindingAdapter("homeRecordThumb")
    public static void setRecordThumb(ImageView view, @Nullable RecordItemVO item) {
        String url = item == null ? null : item.getImageUrl();
        if (!RecordFormatter.isSuccess(item) || TextUtils.isEmpty(url)) {
            Glide.with(view).clear(view);
            view.setImageDrawable(null);
            return;
        }
        Glide.with(view)
                .load(url)
                .transform(new CenterCrop(), new RoundedCorners(DimenUtils.dp(view.getContext(), THUMB_RADIUS_DP)))
                .into(view);
    }
}
