package com.heban.pestai.account.ui.onboarding;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.heban.pestai.account.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A02 单屏数据（仅前端使用的界面模型，本地常量，不来自接口）：插图图标与配色、标题、说明、两个浮动标签。
 */
public final class OnboardingPage {

    @DrawableRes
    public final int icon;
    /** 插图圆底色（主题属性） */
    @AttrRes
    public final int bgAttr;
    /** 插图图标颜色（主题属性） */
    @AttrRes
    public final int fgAttr;
    @StringRes
    public final int title;
    @StringRes
    public final int body;
    @DrawableRes
    public final int chipAIcon;
    @StringRes
    public final int chipAText;
    @DrawableRes
    public final int chipBIcon;
    @StringRes
    public final int chipBText;

    public OnboardingPage(@DrawableRes int icon, @AttrRes int bgAttr, @AttrRes int fgAttr,
                          @StringRes int title, @StringRes int body,
                          @DrawableRes int chipAIcon, @StringRes int chipAText,
                          @DrawableRes int chipBIcon, @StringRes int chipBText) {
        this.icon = icon;
        this.bgAttr = bgAttr;
        this.fgAttr = fgAttr;
        this.title = title;
        this.body = body;
        this.chipAIcon = chipAIcon;
        this.chipAText = chipAText;
        this.chipBIcon = chipBIcon;
        this.chipBText = chipBText;
    }

    // DataBinding 读取用的 getter
    public int getIcon() {
        return icon;
    }

    public int getBgAttr() {
        return bgAttr;
    }

    public int getFgAttr() {
        return fgAttr;
    }

    public int getTitle() {
        return title;
    }

    public int getBody() {
        return body;
    }

    public int getChipAIcon() {
        return chipAIcon;
    }

    public int getChipAText() {
        return chipAText;
    }

    public int getChipBIcon() {
        return chipBIcon;
    }

    public int getChipBText() {
        return chipBText;
    }

    /** 三屏常量（与 UI 设计文档一致） */
    @NonNull
    public static List<OnboardingPage> defaults() {
        return Collections.unmodifiableList(Arrays.asList(
                new OnboardingPage(
                        com.heban.pestai.base.R.drawable.ic_photo_camera_fill,
                        com.google.android.material.R.attr.colorPrimaryContainer,
                        com.google.android.material.R.attr.colorOnPrimaryContainer,
                        R.string.account_onboarding_title_1, R.string.account_onboarding_body_1,
                        com.heban.pestai.base.R.drawable.ic_center_focus_weak, R.string.account_onboarding_chip_1a,
                        com.heban.pestai.base.R.drawable.ic_bolt, R.string.account_onboarding_chip_1b),
                new OnboardingPage(
                        R.drawable.account_ic_fact_check_fill,
                        com.heban.pestai.base.R.attr.colorWarningContainer,
                        com.heban.pestai.base.R.attr.colorOnWarningContainer,
                        R.string.account_onboarding_title_2, R.string.account_onboarding_body_2,
                        com.heban.pestai.base.R.drawable.ic_bug_report, R.string.account_onboarding_chip_2a,
                        com.heban.pestai.base.R.drawable.ic_warning, R.string.account_onboarding_chip_2b),
                new OnboardingPage(
                        com.heban.pestai.base.R.drawable.ic_auto_awesome_fill,
                        com.google.android.material.R.attr.colorTertiaryContainer,
                        com.google.android.material.R.attr.colorOnTertiaryContainer,
                        R.string.account_onboarding_title_3, R.string.account_onboarding_body_3,
                        com.heban.pestai.base.R.drawable.ic_chat, R.string.account_onboarding_chip_3a,
                        com.heban.pestai.base.R.drawable.ic_image, R.string.account_onboarding_chip_3b)));
    }
}
