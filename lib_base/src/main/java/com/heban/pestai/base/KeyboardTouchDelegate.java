package com.heban.pestai.base;

import android.view.MotionEvent;

import androidx.annotation.NonNull;

/**
 * 由主页面 Tab 中的 Fragment 实现：决定这次按下是否按「点击输入框外收起键盘」处理。
 * 宿主 Activity（MainActivity）在 {@link BaseActivity#shouldHideKeyboardOnTouch} 中询问当前 Tab，
 * 例如 AI 问答页点提问模板、待发送图片时保留键盘，避免键盘先收起再弹出造成闪动。
 */
public interface KeyboardTouchDelegate {
    boolean shouldHideKeyboardOnTouch(@NonNull MotionEvent ev);
}
