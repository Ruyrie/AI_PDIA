package com.heban.pestai.account.ui.common;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.account.R;
import com.heban.pestai.base.util.DimenUtils;

/**
 * 两步流程的步骤条（A07 / A08 / A09）：28dp 圆形序号 + 文字，中间 1dp 连接线。
 * <ul>
 *     <li>当前步：primary 底白字，文字 onSurface Medium；</li>
 *     <li>已完成：primaryContainer 底 check 图标，连接线变为 primary；</li>
 *     <li>未到：1dp outline 圆圈，文字 onSurfaceVariant。</li>
 * </ul>
 * DataBinding：{@code app:accountStep="@{vm.step}"}（1 或 2）。
 */
public class AccountStepBar extends LinearLayout {

    private final View circle1;
    private final View circle2;
    private final TextView number1;
    private final TextView number2;
    private final ImageView check1;
    private final TextView label1;
    private final TextView label2;
    private final View line;
    private int step = 1;

    public AccountStepBar(@NonNull Context context) {
        this(context, null);
    }

    public AccountStepBar(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_account_step_bar, this, true);
        circle1 = findViewById(R.id.step_circle_1);
        circle2 = findViewById(R.id.step_circle_2);
        number1 = findViewById(R.id.step_number_1);
        number2 = findViewById(R.id.step_number_2);
        check1 = findViewById(R.id.step_check_1);
        label1 = findViewById(R.id.step_label_1);
        label2 = findViewById(R.id.step_label_2);
        line = findViewById(R.id.step_line);

        int initial = 1;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AccountStepBar);
            label1.setText(a.getString(R.styleable.AccountStepBar_accountStepFirst));
            label2.setText(a.getString(R.styleable.AccountStepBar_accountStepSecond));
            initial = a.getInt(R.styleable.AccountStepBar_accountStep, 1);
            a.recycle();
        }
        applyStep(initial);
    }

    /** 当前步骤：1 或 2（大于 2 视为全部完成） */
    public void setAccountStep(int step) {
        if (step != this.step) {
            applyStep(step);
        }
    }

    public int getAccountStep() {
        return step;
    }

    private void applyStep(int step) {
        this.step = step;
        Context c = getContext();
        int primary = DimenUtils.themeColor(c, androidx.appcompat.R.attr.colorPrimary);
        int onPrimary = DimenUtils.themeColor(c, com.google.android.material.R.attr.colorOnPrimary);
        int primaryContainer = DimenUtils.themeColor(c, com.google.android.material.R.attr.colorPrimaryContainer);
        int onPrimaryContainer = DimenUtils.themeColor(c, com.google.android.material.R.attr.colorOnPrimaryContainer);
        int onSurface = DimenUtils.themeColor(c, com.google.android.material.R.attr.colorOnSurface);
        int onSurfaceVariant = DimenUtils.themeColor(c, com.google.android.material.R.attr.colorOnSurfaceVariant);
        int outlineVariant = DimenUtils.themeColor(c, com.google.android.material.R.attr.colorOutlineVariant);

        boolean firstDone = step >= 2;
        // 第 1 步
        circle1.setBackgroundResource(com.heban.pestai.base.R.drawable.pest_bg_circle);
        circle1.setBackgroundTintList(ColorStateList.valueOf(firstDone ? primaryContainer : primary));
        number1.setVisibility(firstDone ? GONE : VISIBLE);
        number1.setTextColor(onPrimary);
        check1.setVisibility(firstDone ? VISIBLE : GONE);
        check1.setImageTintList(ColorStateList.valueOf(onPrimaryContainer));
        styleLabel(label1, !firstDone, firstDone ? onSurfaceVariant : onSurface);
        // 连接线
        line.setBackgroundColor(firstDone ? primary : outlineVariant);
        // 第 2 步
        boolean secondCurrent = step == 2;
        if (step >= 2) {
            circle2.setBackgroundResource(com.heban.pestai.base.R.drawable.pest_bg_circle);
            circle2.setBackgroundTintList(ColorStateList.valueOf(primary));
            number2.setTextColor(onPrimary);
        } else {
            circle2.setBackgroundTintList(null);
            circle2.setBackgroundResource(R.drawable.account_bg_circle_outline);
            number2.setTextColor(onSurfaceVariant);
        }
        styleLabel(label2, secondCurrent, secondCurrent ? onSurface : onSurfaceVariant);
    }

    private static void styleLabel(@NonNull TextView label, boolean current, int color) {
        label.setTextColor(color);
        label.setTypeface(Typeface.create(Typeface.DEFAULT, current ? 500 : 400, false));
    }
}
