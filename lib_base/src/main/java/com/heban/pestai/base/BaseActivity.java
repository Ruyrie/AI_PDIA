package com.heban.pestai.base;

import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.heban.pestai.base.util.KeyboardUtils;
import com.heban.pestai.base.util.Snackbars;

/**
 * 所有 Activity 的父类。
 * <ul>
 *     <li>开启 Edge-to-Edge（targetSdk 36 强制），浅色页面深色状态栏图标；相机等深色页面重写 {@link #useDarkSystemBars()}。
 *     页面根布局用 {@link com.heban.pestai.base.util.InsetsHelper} 处理系统栏内边距。</li>
 *     <li>点击输入框以外区域自动收起软键盘（课程「touch 事件传播机制」：分发阶段先于子 View 处理）。</li>
 *     <li>通过 {@link #obtainViewModel(Class)} 获取 Dagger 注入的 ViewModel。</li>
 * </ul>
 */
public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SystemBarStyle style = useDarkSystemBars()
                ? SystemBarStyle.dark(Color.TRANSPARENT)
                : SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT);
        EdgeToEdge.enable(this, style, style);
        super.onCreate(savedInstanceState);
    }

    /** 深色界面（如 S01 相机页）返回 true，系统栏图标变为白色 */
    protected boolean useDarkSystemBars() {
        return false;
    }

    protected <VM extends ViewModel> VM obtainViewModel(@NonNull Class<VM> modelClass) {
        return new ViewModelProvider(this, AppHost.from(this).getViewModelFactory()).get(modelClass);
    }

    protected <T> T deps(@NonNull Class<T> depsClass) {
        return AppHost.deps(this, depsClass);
    }

    /** 观察 ViewModel 的一次性提示并以 Snackbar 显示 */
    protected void observeToast(@NonNull BaseViewModel viewModel) {
        viewModel.getToast().observe(this, this::showSnackbar);
    }

    protected void showSnackbar(@Nullable CharSequence message) {
        Snackbars.show(this, message);
    }

    /**
     * 按下时若当前焦点是输入框且触点不在输入框内，收起软键盘并清除焦点；
     * 最后必须交给父类继续分发，否则子 View 收不到事件。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && shouldHideKeyboardOnTouch(ev)) {
            View focus = getCurrentFocus();
            if (focus instanceof EditText && !isTouchInside(focus, ev)) {
                KeyboardUtils.hide(focus);
                focus.clearFocus();
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 是否在这次按下时检查收起键盘。对话页等希望点击发送按钮时保留键盘，可重写并对指定区域返回 false。
     */
    protected boolean shouldHideKeyboardOnTouch(@NonNull MotionEvent ev) {
        return true;
    }

    protected static boolean isTouchInside(@NonNull View view, @NonNull MotionEvent ev) {
        Rect rect = new Rect();
        view.getGlobalVisibleRect(rect);
        return rect.contains((int) ev.getRawX(), (int) ev.getRawY());
    }
}
