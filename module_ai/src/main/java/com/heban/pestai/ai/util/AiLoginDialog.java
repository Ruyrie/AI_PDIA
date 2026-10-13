package com.heban.pestai.ai.util;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.heban.pestai.ai.R;
import com.heban.pestai.common.router.RouterPath;

/**
 * 游客拦截对话框：「登录后才能使用」，按钮「取消」「去登录」（进入 A03）。
 */
public final class AiLoginDialog {

    private AiLoginDialog() {
    }

    public static void show(@NonNull Context context) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ai_login_title)
                .setMessage(R.string.ai_login_message)
                .setNegativeButton(com.heban.pestai.base.R.string.pest_cancel, null)
                .setPositiveButton(R.string.ai_go_login,
                        (dialog, which) -> context.startActivity(RouterPath.intent(context, RouterPath.LOGIN)))
                .show();
    }
}
