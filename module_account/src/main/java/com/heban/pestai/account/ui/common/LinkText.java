package com.heban.pestai.account.ui.common;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.heban.pestai.account.R;
import com.heban.pestai.base.util.DimenUtils;
import com.heban.pestai.common.router.RouterPath;

/**
 * 文字中的可点链接（primary 色、Medium、无下划线）：协议行的《用户协议》《隐私政策》、「改用 密码登录」等。
 */
public final class LinkText {

    private LinkText() {
    }

    /** 「我已阅读并同意《用户协议》和《隐私政策》」，书名号部分跳转用户协议页（module_mine 实现） */
    public static void bindAgreement(@NonNull TextView textView) {
        Context context = textView.getContext();
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(context.getString(R.string.account_agreement_prefix)).append(' ');
        appendLink(sb, context.getString(R.string.account_agreement_user), textView,
                v -> openAgreement(v.getContext(), RouterPath.AGREEMENT_USER));
        sb.append(' ').append(context.getString(R.string.account_agreement_and)).append(' ');
        appendLink(sb, context.getString(R.string.account_agreement_privacy), textView,
                v -> openAgreement(v.getContext(), RouterPath.AGREEMENT_PRIVACY));
        apply(textView, sb);
    }

    /** 普通文字后接一个可点链接 */
    public static void bind(@NonNull TextView textView, @NonNull CharSequence text, @NonNull CharSequence link,
                            @NonNull View.OnClickListener onClick) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        appendLink(sb, link, textView, onClick);
        apply(textView, sb);
    }

    private static void appendLink(@NonNull SpannableStringBuilder sb, @NonNull CharSequence link,
                                   @NonNull TextView textView, @NonNull View.OnClickListener onClick) {
        int start = sb.length();
        sb.append(link);
        final int color = DimenUtils.themeColor(textView.getContext(), androidx.appcompat.R.attr.colorPrimary);
        sb.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                onClick.onClick(widget);
            }

            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                ds.setColor(color);
                ds.setUnderlineText(false);
                ds.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
            }
        }, start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void apply(@NonNull TextView textView, @NonNull CharSequence text) {
        textView.setText(text);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        textView.setHighlightColor(android.graphics.Color.TRANSPARENT);
    }

    private static void openAgreement(@NonNull Context context, @NonNull String type) {
        try {
            context.startActivity(RouterPath.intent(context, RouterPath.AGREEMENT)
                    .putExtra(RouterPath.EXTRA_AGREEMENT_TYPE, type));
        } catch (ActivityNotFoundException ignored) {
            // 用户协议页由 module_mine 提供，单独调试本组件时可能不存在
        }
    }
}
