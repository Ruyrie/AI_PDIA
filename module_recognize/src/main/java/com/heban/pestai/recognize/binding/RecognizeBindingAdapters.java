package com.heban.pestai.recognize.binding;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.databinding.BindingAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec;
import com.google.android.material.progressindicator.IndeterminateDrawable;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.heban.pestai.base.util.DimenUtils;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.model.OtherItem;
import com.heban.pestai.recognize.model.SeverityLevel;
import com.heban.pestai.recognize.model.StepState;
import com.heban.pestai.recognize.model.TagItem;
import com.heban.pestai.recognize.ui.result.OtherAdapter;
import com.heban.pestai.recognize.util.Formats;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * module_recognize 的 BindingAdapter（属性名统一加 scan 前缀，不与 lib_base 的通用属性重名）。
 * 单字段的显示格式化集中在这里：布局把 VO 的原始字段交给自定义属性，由这里转成文字、颜色或图片。
 */
public final class RecognizeBindingAdapters {

    private static final long FADE_MS = 150;
    private static final long CHEVRON_MS = 200;

    private RecognizeBindingAdapters() {
    }

    // ---------- 通用 ----------

    /** 文字为空白或列表为空时 GONE */
    @BindingAdapter("scanVisibleIfNotEmpty")
    public static void setVisibleIfNotEmpty(View view, @Nullable Object value) {
        boolean visible;
        if (value instanceof CharSequence) {
            visible = value.toString().trim().length() > 0;
        } else if (value instanceof Collection) {
            visible = !((Collection<?>) value).isEmpty();
        } else {
            visible = value != null;
        }
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @BindingAdapter("scanIconRes")
    public static void setIconRes(ImageView view, int iconRes) {
        if (iconRes != 0) {
            view.setImageResource(iconRes);
        }
    }

    /** 用主题颜色属性给背景着色（保留背景形状） */
    @BindingAdapter("scanBackgroundTintAttr")
    public static void setBackgroundTintAttr(View view, @AttrRes int attr) {
        if (attr != 0) {
            view.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(view, attr)));
        }
    }

    /** 用主题颜色属性给图标着色 */
    @BindingAdapter("scanTintAttr")
    public static void setTintAttr(ImageView view, @AttrRes int attr) {
        if (attr != 0) {
            view.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(view, attr)));
        }
    }

    /** 模式图标：PEST bug_report，CROP grass */
    @BindingAdapter("scanModeIcon")
    public static void setModeIcon(ImageView view, @Nullable String mode) {
        view.setImageResource(Formats.modeIconRes(mode));
    }

    /**
     * 按钮图标与进行中状态：loading 为 true 时图标换成 18dp 圆形进度（颜色同文字）；
     * iconRes 为 0 时恢复布局中 app:icon 设置的图标。
     */
    @BindingAdapter(value = {"scanButtonIcon", "scanLoading"}, requireAll = false)
    public static void setButtonIcon(MaterialButton button, int iconRes, boolean loading) {
        if (button.getTag(R.id.scan_tag_idle_icon) == null && button.getIcon() != null) {
            button.setTag(R.id.scan_tag_idle_icon, button.getIcon());
        }
        if (loading) {
            Context context = button.getContext();
            CircularProgressIndicatorSpec spec = new CircularProgressIndicatorSpec(context, null);
            spec.indicatorSize = DimenUtils.dp(context, 18);
            spec.indicatorInset = 0;
            spec.trackThickness = DimenUtils.dp(context, 2);
            spec.trackColor = 0x00000000;
            spec.indicatorColors = new int[]{button.getCurrentTextColor()};
            button.setIcon(IndeterminateDrawable.createCircularDrawable(context, spec));
        } else if (iconRes != 0) {
            button.setIconResource(iconRes);
            button.setTag(R.id.scan_tag_idle_icon, button.getIcon());
        } else {
            Object idle = button.getTag(R.id.scan_tag_idle_icon);
            button.setIcon(idle instanceof Drawable ? (Drawable) idle : null);
        }
    }

    // ---------- S01 ----------

    /** 闪光灯图标与无障碍文字：开启时 flash_on「关闭闪光灯」，关闭时 flash_off「打开闪光灯」 */
    @BindingAdapter("scanFlashOn")
    public static void setFlashOn(ImageView view, boolean on) {
        view.setImageResource(on ? com.heban.pestai.base.R.drawable.ic_flash_on
                : com.heban.pestai.base.R.drawable.ic_flash_off);
        view.setContentDescription(view.getContext().getString(
                on ? R.string.scan_flash_turn_off : R.string.scan_flash_turn_on));
    }

    // ---------- S02 ----------

    /** 总进度：平滑过渡到新值 */
    @BindingAdapter("scanProgress")
    public static void setProgress(LinearProgressIndicator view, @Nullable Integer progress) {
        int value = progress == null ? 0 : progress;
        if (view.getProgress() != value) {
            view.setProgressCompat(value, true);
        }
    }

    /**
     * 步骤状态图标。容器内第 0 个子 View 为 32dp 圆形图标（完成 check / 等待 more_horiz），
     * 第 1 个为 20dp 旋转圆环（进行中）；状态切换时 150ms 淡入淡出。
     */
    @BindingAdapter("scanStepState")
    public static void setStepState(ViewGroup container, @Nullable StepState state) {
        StepState next = state == null ? StepState.WAITING : state;
        Object prev = container.getTag(R.id.scan_tag_step_state);
        if (next == prev) {
            return;
        }
        container.setTag(R.id.scan_tag_step_state, next);
        ImageView circle = (ImageView) container.getChildAt(0);
        View spinner = container.getChildAt(1);
        boolean animate = prev != null && container.isAttachedToWindow();
        if (next == StepState.RUNNING) {
            switchViews(circle, spinner, animate);
            return;
        }
        boolean done = next == StepState.DONE;
        circle.setImageResource(done ? com.heban.pestai.base.R.drawable.ic_check
                : com.heban.pestai.base.R.drawable.ic_more_horiz);
        circle.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(circle, done
                ? com.google.android.material.R.attr.colorPrimaryContainer
                : com.google.android.material.R.attr.colorSurfaceContainerHighest)));
        circle.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(circle, done
                ? com.google.android.material.R.attr.colorOnPrimaryContainer
                : com.google.android.material.R.attr.colorOutline)));
        switchViews(spinner, circle, animate);
    }

    private static void switchViews(View out, View in, boolean animate) {
        if (in.getVisibility() == View.VISIBLE && out.getVisibility() != View.VISIBLE) {
            // 已是目标视图（如等待 → 完成），只更新了图标
            return;
        }
        out.animate().cancel();
        in.animate().cancel();
        if (!animate) {
            out.setVisibility(View.GONE);
            in.setAlpha(1f);
            in.setVisibility(View.VISIBLE);
            return;
        }
        out.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            out.setVisibility(View.GONE);
            out.setAlpha(1f);
        }).start();
        in.setAlpha(0f);
        in.setVisibility(View.VISIBLE);
        in.animate().alpha(1f).setDuration(FADE_MS).start();
    }

    /** 步骤名：等待中为 onSurfaceVariant，其余 onSurface */
    @BindingAdapter("scanStepName")
    public static void setStepName(TextView view, @Nullable StepState state) {
        view.setTextColor(MaterialColors.getColor(view, state == null || state == StepState.WAITING
                ? com.google.android.material.R.attr.colorOnSurfaceVariant
                : com.google.android.material.R.attr.colorOnSurface));
    }

    // ---------- R01 ----------

    /** 严重程度标签：为空或未知取值时隐藏；否则设置文字、底色、文字色与起始图标（图标与文字同色） */
    @BindingAdapter("scanSeverity")
    public static void setSeverity(TextView view, @Nullable String severity) {
        SeverityLevel level = SeverityLevel.of(severity);
        if (level == null) {
            view.setVisibility(View.GONE);
            return;
        }
        int bg = MaterialColors.getColor(view, level.bgAttr);
        int fg = MaterialColors.getColor(view, level.fgAttr);
        view.setVisibility(View.VISIBLE);
        view.setText(level.text);
        view.setBackgroundTintList(ColorStateList.valueOf(bg));
        view.setTextColor(fg);
        Drawable icon = ContextCompat.getDrawable(view.getContext(), level.iconRes);
        if (icon != null) {
            int size = DimenUtils.dp(view.getContext(), 18);
            icon = icon.mutate();
            icon.setBounds(0, 0, size, size);
        }
        view.setCompoundDrawablesRelative(icon, null, null, null);
        TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(fg));
    }

    /** 「匹配度 92%」 */
    @BindingAdapter("scanConfidenceText")
    public static void setConfidenceText(TextView view, @Nullable Double confidence) {
        view.setText(view.getContext().getString(R.string.scan_result_confidence, Formats.percent(confidence)));
    }

    /** 其他可能的百分比「6%」 */
    @BindingAdapter("scanPercentText")
    public static void setPercentText(TextView view, double confidence) {
        view.setText(Formats.percentText(confidence));
    }

    /** 其他可能的小进度条：百分比的 2 倍（6% → 12%） */
    @BindingAdapter("scanBarPercent")
    public static void setBarPercent(LinearProgressIndicator view, double confidence) {
        view.setProgressCompat(Formats.barPercent(confidence), false);
    }

    /** 特征标签：按列表逐个生成 24dp 小标签并按样式着色；列表为空时整行隐藏 */
    @BindingAdapter("scanTags")
    public static void setTags(LinearLayout container, @Nullable List<TagItem> tags) {
        container.removeAllViews();
        if (tags == null || tags.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(container.getContext());
        for (int i = 0; i < tags.size(); i++) {
            TagItem tag = tags.get(i);
            TextView view = (TextView) inflater.inflate(R.layout.item_scan_tag, container, false);
            view.setText(tag.text != null ? tag.text : container.getContext().getString(tag.textRes));
            view.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(container, tag.style.bgAttr)));
            view.setTextColor(MaterialColors.getColor(container, tag.style.fgAttr));
            if (i > 0) {
                ((ViewGroup.MarginLayoutParams) view.getLayoutParams())
                        .setMarginStart(DimenUtils.dp(container.getContext(), 8));
            }
            container.addView(view);
        }
    }

    /** 其他可能列表：交给 OtherAdapter.submit()，DiffUtil 局部刷新（只有展开状态变化时带过渡） */
    @BindingAdapter("scanCandidates")
    public static void setCandidates(RecyclerView view, @Nullable List<OtherItem> items) {
        if (view.getAdapter() instanceof OtherAdapter) {
            ((OtherAdapter) view.getAdapter()).submit(items);
        }
    }

    /**
     * 其他可能的行尾箭头（expand_more）：可展开的行显示，展开时旋转 180°；
     * 不可展开的行在列表中有可展开行时 INVISIBLE 占位（各行进度条对齐），否则 GONE。
     * 同一行（名称相同）展开状态变化时 200ms 旋转动画；首次绑定、复用到其他行时直接设置。
     */
    @BindingAdapter("scanOtherChevron")
    public static void setOtherChevron(ImageView view, @Nullable OtherItem oldItem, @Nullable OtherItem item) {
        view.animate().cancel();
        if (item == null || !item.isExpandable()) {
            view.setRotation(0f);
            view.setVisibility(item != null && item.isReserveChevron() ? View.INVISIBLE : View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        float target = item.isExpanded() ? 180f : 0f;
        boolean sameRow = oldItem != null && oldItem.isExpandable()
                && Objects.equals(oldItem.getName(), item.getName());
        if (sameRow && view.isAttachedToWindow() && view.getRotation() != target) {
            view.animate().rotation(target).setDuration(CHEVRON_MS).start();
        } else {
            view.setRotation(target);
        }
    }

    // ---------- R02 ----------

    /** 建议列表：序号圆点 + 文字，条目间 1dp outlineVariant 分割线 */
    @BindingAdapter("scanTips")
    public static void setTips(LinearLayout container, @Nullable List<String> tips) {
        container.removeAllViews();
        if (tips == null) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(container.getContext());
        for (int i = 0; i < tips.size(); i++) {
            if (i > 0) {
                View divider = new View(container.getContext());
                divider.setBackgroundColor(MaterialColors.getColor(container,
                        com.google.android.material.R.attr.colorOutlineVariant));
                container.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, DimenUtils.dp(container.getContext(), 1)));
            }
            View row = inflater.inflate(R.layout.item_scan_tip, container, false);
            ((TextView) row.findViewById(R.id.scanTipNumber)).setText(String.valueOf(i + 1));
            ((TextView) row.findViewById(R.id.scanTipText)).setText(tips.get(i));
            container.addView(row);
        }
    }
}
