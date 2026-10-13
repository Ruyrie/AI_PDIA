package com.heban.pestai.base;

import android.view.View;

import androidx.annotation.Nullable;

/**
 * 由带底部导航 / 底部操作栏的 Activity 实现，Snackbar 会显示在该 View 上方。
 */
public interface SnackbarAnchorProvider {
    @Nullable
    View getSnackbarAnchor();
}
