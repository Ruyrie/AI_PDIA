package com.heban.pestai.mine.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.heban.pestai.base.util.DimenUtils;
import com.heban.pestai.base.util.Snackbars;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.mine.R;

/**
 * module_mine 页面共用的小工具：带图标的 Snackbar、隐式跳转、游客登录提示对话框。
 */
public final class MineUi {

    private static final String TAG = "MineUi";

    private MineUi() {
    }

    /**
     * 统一样式 Snackbar（inverseSurface 底，4 秒），左侧 20dp 图标用 inversePrimary 着色
     * （清理完成 check_circle、功能尚未开放 schedule）。连续调用时新的替换旧的，不叠加。
     */
    public static void showSnackbar(@NonNull Activity activity, @NonNull CharSequence message, @DrawableRes int icon) {
        Snackbar snackbar = Snackbars.show(activity, message, icon, null, null);
        if (snackbar == null) {
            return;
        }
        TextView text = snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        if (text == null) {
            return;
        }
        Drawable drawable = text.getCompoundDrawablesRelative()[0];
        if (drawable != null) {
            int size = DimenUtils.dp(activity, 20);
            drawable.setBounds(0, 0, size, size);
            text.setCompoundDrawablesRelative(drawable, null, null, null);
        }
        int iconColor = MaterialColors.getColor(text, com.google.android.material.R.attr.colorPrimaryInverse);
        text.setCompoundDrawableTintList(ColorStateList.valueOf(iconColor));
    }

    /** 隐式跳转其他组件的页面；目标页面尚未声明（组件开发中）时提示而不崩溃 */
    public static void start(@NonNull Context context, @NonNull Intent intent) {
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "页面未声明：" + intent.getAction(), e);
            if (context instanceof Activity) {
                Snackbars.show((Activity) context, context.getString(R.string.mine_page_not_ready));
            }
        }
    }

    public static void open(@NonNull Context context, @NonNull String action) {
        start(context, RouterPath.intent(context, action));
    }

    /** 游客点需要登录的入口：「登录后才能使用」，按钮「取消」「去登录」；去登录普通打开 A03，不清栈 */
    public static void showNeedLogin(@NonNull Context context) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.mine_need_login_title)
                .setNegativeButton(com.heban.pestai.base.R.string.pest_cancel, null)
                .setPositiveButton(R.string.mine_go_login, (dialog, which) -> open(context, RouterPath.LOGIN))
                .show();
    }
}
