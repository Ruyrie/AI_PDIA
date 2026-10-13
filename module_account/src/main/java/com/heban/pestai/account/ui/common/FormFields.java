package com.heban.pestai.account.ui.common;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputLayout;
import com.heban.pestai.account.R;
import com.heban.pestai.base.util.DimenUtils;

/**
 * 表单输入框的公共装饰：
 * <ul>
 *     <li>手机号：「+86 |」前缀 + 3-4-4 自动分隔（只支持 +86，所有页面都不带下拉箭头）；</li>
 *     <li>恢复码：自动转大写、按 4 位插入连字符；</li>
 *     <li>验证码：右侧「| 获取验证码 / {N}s 后重发」文字按钮（使用 TextInputLayout 的后缀 TextView，
 *     文字与可点状态由 DataBinding 的 app:suffixText 与 app:accountCodeActionEnabled 驱动）。</li>
 * </ul>
 */
public final class FormFields {

    private FormFields() {
    }

    /** 手机号输入框：「+86」后接 1dp 竖线，输入时按 3-4-4 分隔 */
    public static void setupPhone(@NonNull TextInputLayout layout) {
        Context context = layout.getContext();
        TextView prefix = layout.getPrefixTextView();
        prefix.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.account_prefix_divider, 0);
        prefix.setCompoundDrawablePadding(DimenUtils.dp(context, 12));
        prefix.setGravity(Gravity.CENTER_VERTICAL);
        EditText editText = layout.getEditText();
        if (editText != null) {
            PhoneFormatWatcher.attach(editText);
        }
    }

    /** 恢复码输入框：自动转大写、按 4 位插入连字符、最多 12 位有效字符 */
    public static void setupRecoveryCode(@NonNull TextInputLayout layout) {
        EditText editText = layout.getEditText();
        if (editText != null) {
            RecoveryCodeFormatWatcher.attach(editText);
        }
    }

    /** 验证码输入框右侧的「获取验证码」文字按钮 */
    public static void setupCodeAction(@NonNull TextInputLayout layout, @NonNull View.OnClickListener onClick) {
        Context context = layout.getContext();
        TextView suffix = layout.getSuffixTextView();
        suffix.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.account_suffix_divider, 0, 0, 0);
        suffix.setGravity(Gravity.CENTER_VERTICAL);
        suffix.setMinHeight(DimenUtils.dp(context, 48));
        suffix.setPaddingRelative(0, 0, DimenUtils.dp(context, 4), 0);
        suffix.setBackgroundResource(R.drawable.account_bg_text_action);
        layout.setSuffixTextColor(ContextCompat.getColorStateList(context, R.color.account_code_action));
        suffix.setClickable(true);
        suffix.setOnClickListener(onClick);
    }

    /** 未勾选协议时复选框行轻微抖动 */
    public static void shake(@NonNull View view) {
        float d = DimenUtils.dp(view.getContext(), 6);
        ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0, d, -d, d * 0.6f, -d * 0.6f, d * 0.3f, 0)
                .setDuration(400)
                .start();
    }
}
