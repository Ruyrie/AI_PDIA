package com.heban.pestai.account.ui.common;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.TextAppearanceSpan;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.databinding.BindingAdapter;

import com.google.android.material.textfield.TextInputLayout;
import com.heban.pestai.account.R;
import com.heban.pestai.account.util.PasswordRules;
import com.heban.pestai.base.util.DimenUtils;

/**
 * module_account 的 BindingAdapter。属性名统一加 account 前缀，避免与 lib_base 的通用属性重名。
 */
public final class AccountBindingAdapters {

    private static final int COLOR_ANIM_MS = 150;

    private AccountBindingAdapters() {
    }

    // ---------- 通用 ----------

    /** 图标资源（int） */
    /**
     * 登录按钮文字：提交中为空（按钮内显示进度圈），否则按登录方式显示「登录」或「登录 / 注册」。
     * 不在布局里写嵌套三元表达式：DataBinding 为其生成的脏标记顺序有误，重新绑定时内层结果不会计算。
     */
    @BindingAdapter(value = {"accountSubmitLoading", "accountSubmitSms"})
    public static void setSubmitText(TextView view, boolean loading, boolean sms) {
        if (loading) {
            view.setText("");
        } else {
            view.setText(sms ? R.string.account_login_sms_button : R.string.account_login_button);
        }
    }

    /**
     * A12 生成按钮文字：提交中为空（按钮内显示进度圈）；引导模式生成失败后为「重试」；
     * 否则按状态显示「生成恢复码」或「重新生成恢复码」。同样避免在布局里写嵌套三元表达式。
     */
    @BindingAdapter(value = {"accountGenerateLoading", "accountGenerateAgain", "accountGenerateRetry"},
            requireAll = false)
    public static void setGenerateText(TextView view, boolean loading, boolean again, boolean retry) {
        if (loading) {
            view.setText("");
        } else if (retry) {
            view.setText(R.string.account_recovery_retry);
        } else {
            view.setText(again ? R.string.account_recovery_regenerate : R.string.account_recovery_generate);
        }
    }

    /**
     * A06 底部按钮文字：保存中为空（按钮内显示进度圈）；编辑资料模式为「保存」，完善资料模式为「开始使用」。
     * 同样避免在布局里写嵌套三元表达式。
     */
    @BindingAdapter(value = {"accountProfileSaving", "accountProfileEdit"})
    public static void setProfileSubmitText(TextView view, boolean saving, boolean edit) {
        if (saving) {
            view.setText("");
        } else {
            view.setText(edit ? R.string.account_profile_save : R.string.account_start_use);
        }
    }

    @BindingAdapter("accountSrc")
    public static void setSrc(ImageView view, @DrawableRes int res) {
        if (res != 0) {
            view.setImageResource(res);
        }
    }

    /** 图标着色：主题属性 */
    @BindingAdapter("accountTintAttr")
    public static void setTintAttr(ImageView view, @AttrRes int attr) {
        if (attr != 0) {
            view.setImageTintList(ColorStateList.valueOf(DimenUtils.themeColor(view.getContext(), attr)));
        }
    }

    /** 背景着色：主题属性 */
    @BindingAdapter("accountBackgroundTintAttr")
    public static void setBackgroundTintAttr(View view, @AttrRes int attr) {
        if (attr != 0) {
            view.setBackgroundTintList(ColorStateList.valueOf(DimenUtils.themeColor(view.getContext(), attr)));
        }
    }

    // ---------- 输入框 ----------

    /**
     * 输入框下方的提示行：error 为 true 时以错误样式显示（error 色），否则作为 helper 文字（onSurfaceVariant）；
     * 文字为空时隐藏。
     */
    @BindingAdapter(value = {"accountHelper", "accountHelperError"}, requireAll = false)
    public static void setHelper(TextInputLayout layout, @Nullable CharSequence text, boolean error) {
        if (TextUtils.isEmpty(text)) {
            layout.setError(null);
            layout.setErrorEnabled(false);
            layout.setHelperText(null);
        } else if (error) {
            layout.setHelperText(null);
            layout.setError(text);
        } else {
            layout.setError(null);
            layout.setErrorEnabled(false);
            layout.setHelperText(text);
        }
    }

    /** 「获取验证码」文字按钮是否可点（文字由 app:suffixText 绑定） */
    @BindingAdapter("accountCodeActionEnabled")
    public static void setCodeActionEnabled(TextInputLayout layout, boolean enabled) {
        TextView suffix = layout.getSuffixTextView();
        suffix.setEnabled(enabled);
        suffix.setClickable(enabled);
    }

    /** 密码明文 / 密文切换，光标保持在末尾 */
    @BindingAdapter("accountPasswordVisible")
    public static void setPasswordVisible(EditText editText, boolean visible) {
        boolean currentlyVisible = editText.getTransformationMethod() instanceof HideReturnsTransformationMethod;
        if (currentlyVisible == visible) {
            return;
        }
        editText.setTransformationMethod(visible
                ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        editText.setSelection(editText.length());
    }

    /** 眼睛按钮图标：visibility ↔ visibility_off */
    @BindingAdapter("accountEyeOpen")
    public static void setEyeOpen(TextInputLayout layout, boolean visible) {
        layout.setEndIconDrawable(visible
                ? com.heban.pestai.base.R.drawable.ic_visibility_off
                : com.heban.pestai.base.R.drawable.ic_visibility);
    }

    // ---------- 密码强度与规则 ----------

    /**
     * 三段强度条的单段：index（0–2）小于 level 时着色（弱 error / 中 warning / 强 primary），否则为空段色；150ms 颜色动画。
     */
    @BindingAdapter(value = {"accountStrengthLevel", "accountSegmentIndex"})
    public static void setStrengthSegment(View view, int level, int index) {
        Context c = view.getContext();
        int target = index < level
                ? levelColor(c, level)
                : DimenUtils.themeColor(c, com.google.android.material.R.attr.colorSurfaceContainerHighest);
        animateTint(view, target);
    }

    /** 「强度：弱 / 中 / 强」文字与颜色 */
    @BindingAdapter("accountStrengthText")
    public static void setStrengthText(TextView view, int level) {
        view.setText(PasswordRules.strengthText(level));
        view.setTextColor(level > 0 ? levelColor(view.getContext(), level)
                : DimenUtils.themeColor(view.getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
    }

    private static int levelColor(Context c, int level) {
        switch (level) {
            case PasswordRules.LEVEL_WEAK:
                return DimenUtils.themeColor(c, androidx.appcompat.R.attr.colorError);
            case PasswordRules.LEVEL_MEDIUM:
                return DimenUtils.themeColor(c, com.heban.pestai.base.R.attr.colorWarning);
            default:
                return DimenUtils.themeColor(c, androidx.appcompat.R.attr.colorPrimary);
        }
    }

    private static void animateTint(View view, int target) {
        ColorStateList current = view.getBackgroundTintList();
        if (current == null || !view.isLaidOut()) {
            view.setBackgroundTintList(ColorStateList.valueOf(target));
            return;
        }
        int from = current.getDefaultColor();
        if (from == target) {
            return;
        }
        Object running = view.getTag(R.id.account_tag_color_animator);
        if (running instanceof ValueAnimator) {
            ((ValueAnimator) running).cancel();
        }
        ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), from, target);
        animator.setDuration(COLOR_ANIM_MS);
        animator.addUpdateListener(a -> view.setBackgroundTintList(ColorStateList.valueOf((int) a.getAnimatedValue())));
        view.setTag(R.id.account_tag_color_animator, animator);
        animator.start();
    }

    /** A05 规则行图标：满足 check_circle（primary），未满足 radio_button_unchecked（outline） */
    @BindingAdapter("accountRuleMet")
    public static void setRuleMet(ImageView view, boolean met) {
        Context c = view.getContext();
        view.setImageResource(met
                ? com.heban.pestai.base.R.drawable.ic_check_circle_fill
                : com.heban.pestai.base.R.drawable.ic_radio_button_unchecked);
        view.setImageTintList(ColorStateList.valueOf(DimenUtils.themeColor(c, met
                ? androidx.appcompat.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOutline)));
    }

    /** 规则文字：满足 onSurfaceVariant，未满足 outline */
    @BindingAdapter("accountRuleTextMet")
    public static void setRuleTextMet(TextView view, boolean met) {
        view.setTextColor(DimenUtils.themeColor(view.getContext(), met
                ? com.google.android.material.R.attr.colorOnSurfaceVariant
                : com.google.android.material.R.attr.colorOutline));
    }

    /**
     * A08 规则卡图标（三态）：0 待校验 / 未满足 → check_circle outline 色；1 满足 → primary；2 未通过 → cancel error 色。
     */
    @BindingAdapter("accountRuleState")
    public static void setRuleState(ImageView view, int state) {
        Context c = view.getContext();
        if (state == 2) {
            view.setImageResource(com.heban.pestai.base.R.drawable.ic_cancel_fill);
            view.setImageTintList(ColorStateList.valueOf(DimenUtils.themeColor(c, androidx.appcompat.R.attr.colorError)));
        } else {
            view.setImageResource(com.heban.pestai.base.R.drawable.ic_check_circle_fill);
            view.setImageTintList(ColorStateList.valueOf(DimenUtils.themeColor(c, state == 1
                    ? androidx.appcompat.R.attr.colorPrimary
                    : com.google.android.material.R.attr.colorOutline)));
        }
    }

    /** A08 规则卡文字：未通过（2）为 error 色，其余 onSurface */
    @BindingAdapter("accountRuleTextState")
    public static void setRuleTextState(TextView view, int state) {
        view.setTextColor(DimenUtils.themeColor(view.getContext(), state == 2
                ? androidx.appcompat.R.attr.colorError
                : com.google.android.material.R.attr.colorOnSurface));
    }

    // ---------- A06 ----------

    /** 「你主要种什么？（已选 3 项，可多选）」：括号部分为 BodyMedium onSurfaceVariant */
    @BindingAdapter("accountCropTitle")
    public static void setCropTitle(TextView view, @Nullable String title) {
        if (title == null) {
            view.setText(null);
            return;
        }
        int split = title.indexOf('（');
        if (split < 0) {
            view.setText(title);
            return;
        }
        SpannableString text = new SpannableString(title);
        Context c = view.getContext();
        text.setSpan(new TextAppearanceSpan(c, com.heban.pestai.base.R.style.TextAppearance_Pest_BodyMedium),
                split, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(DimenUtils.themeColor(c,
                        com.google.android.material.R.attr.colorOnSurfaceVariant)),
                split, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text);
    }
}
