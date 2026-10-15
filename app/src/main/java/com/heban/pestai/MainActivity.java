package com.heban.pestai;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;

import com.heban.pestai.ai.AskAiFragment;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.KeyboardTouchDelegate;
import com.heban.pestai.base.SnackbarAnchorProvider;
import com.heban.pestai.base.TabReselectable;
import com.heban.pestai.common.MainViewModel;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.databinding.ActivityMainBinding;
import com.heban.pestai.home.HomeFragment;
import com.heban.pestai.mine.MineFragment;

/**
 * 主页面（RouterPath.MAIN）：底部导航承载 HomeFragment / AskAiFragment / MineFragment。
 * <ul>
 *     <li>Tab 状态保存在 Activity 作用域的 MainViewModel，Fragment 调用 selectTab() 切换。</li>
 *     <li>切换用 show / hide + 淡入淡出；隐藏的 Fragment 生命周期限制为 STARTED，
 *     因此 Fragment 的 onResume() 只在当前可见时回调，可在其中刷新数据。</li>
 *     <li>Intent 参数 EXTRA_TAB 指定默认 Tab（如 R01「追问 AI」后回到 AI 问答）。</li>
 *     <li>返回键：非首页 Tab 先回到首页；首页 2 秒内连按两次才退出，第一次提示「再按一次退出程序」。</li>
 * </ul>
 */
public class MainActivity extends BaseActivity implements SnackbarAnchorProvider {

    private static final String[] TAGS = {"tab_home", "tab_ai", "tab_mine"};
    /** 首页两次按返回的最大间隔（与 Toast.LENGTH_SHORT 显示时长一致） */
    private static final long EXIT_INTERVAL_MS = 2000;

    private ActivityMainBinding binding;
    private MainViewModel mainViewModel;
    /** 首页上一次按返回的时间（SystemClock.uptimeMillis），0 表示没有按过 */
    private long lastBackAt;
    @Nullable
    private Toast exitToast;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        mainViewModel = obtainViewModel(MainViewModel.class);

        setupInsets();
        binding.mainBottomNav.setOnItemSelectedListener(item -> {
            mainViewModel.selectTab(tabOf(item.getItemId()));
            return true;
        });
        binding.mainBottomNav.setOnItemReselectedListener(item -> {
            Fragment fragment = getSupportFragmentManager().findFragmentByTag(TAGS[tabOf(item.getItemId())]);
            if (fragment instanceof TabReselectable) {
                ((TabReselectable) fragment).onTabReselected();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBack();
            }
        });

        mainViewModel.getCurrentTab().observe(this, tab -> {
            showTab(tab);
            // 切换 Tab 后重新计算「再按一次退出」
            lastBackAt = 0;
        });
        if (savedInstanceState == null) {
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /**
     * 返回键：非首页 Tab 先回到首页；首页第一次按提示「再按一次退出程序」，2 秒内再按一次才退出（防误触）。
     */
    private void onBack() {
        Integer tab = mainViewModel.getCurrentTab().getValue();
        if (tab != null && tab != MainViewModel.TAB_HOME) {
            mainViewModel.selectTab(MainViewModel.TAB_HOME);
            return;
        }
        long now = SystemClock.uptimeMillis();
        cancelExitToast();
        if (lastBackAt > 0 && now - lastBackAt <= EXIT_INTERVAL_MS) {
            finish();
            return;
        }
        lastBackAt = now;
        exitToast = Toast.makeText(this, R.string.main_exit_hint, Toast.LENGTH_SHORT);
        exitToast.show();
    }

    private void cancelExitToast() {
        if (exitToast != null) {
            exitToast.cancel();
            exitToast = null;
        }
    }

    private void handleIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }
        int tab = intent.getIntExtra(RouterPath.EXTRA_TAB, -1);
        if (tab >= MainViewModel.TAB_HOME) {
            mainViewModel.selectTab(tab);
        }
    }

    /** 顶部避开状态栏；软键盘弹出时隐藏底部导航，内容区底部让出键盘高度（AI 问答输入框） */
    private void setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            binding.mainBottomNav.setVisibility(imeVisible ? View.GONE : View.VISIBLE);
            binding.mainContainer.setPadding(bars.left, bars.top, bars.right, imeVisible ? imeBottom : 0);
            return insets;
        });
    }

    private void showTab(@Nullable Integer tab) {
        if (tab == null) {
            return;
        }
        int itemId = itemIdOf(tab);
        if (binding.mainBottomNav.getSelectedItemId() != itemId) {
            binding.mainBottomNav.setSelectedItemId(itemId);
        }
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction()
                .setCustomAnimations(com.heban.pestai.base.R.anim.pest_fade_in, com.heban.pestai.base.R.anim.pest_fade_out)
                .setReorderingAllowed(true);
        for (int i = 0; i < TAGS.length; i++) {
            Fragment fragment = fm.findFragmentByTag(TAGS[i]);
            if (i == tab) {
                if (fragment == null) {
                    fragment = createFragment(i);
                    tx.add(R.id.main_container, fragment, TAGS[i]);
                } else {
                    tx.show(fragment);
                }
                tx.setMaxLifecycle(fragment, Lifecycle.State.RESUMED);
            } else if (fragment != null) {
                tx.hide(fragment);
                tx.setMaxLifecycle(fragment, Lifecycle.State.STARTED);
            }
        }
        tx.commitNow();
    }

    @NonNull
    private static Fragment createFragment(int tab) {
        switch (tab) {
            case MainViewModel.TAB_AI:
                return new AskAiFragment();
            case MainViewModel.TAB_MINE:
                return new MineFragment();
            case MainViewModel.TAB_HOME:
            default:
                return new HomeFragment();
        }
    }

    private static int tabOf(int itemId) {
        if (itemId == R.id.nav_ai) {
            return MainViewModel.TAB_AI;
        }
        if (itemId == R.id.nav_mine) {
            return MainViewModel.TAB_MINE;
        }
        return MainViewModel.TAB_HOME;
    }

    private static int itemIdOf(int tab) {
        switch (tab) {
            case MainViewModel.TAB_AI:
                return R.id.nav_ai;
            case MainViewModel.TAB_MINE:
                return R.id.nav_mine;
            case MainViewModel.TAB_HOME:
            default:
                return R.id.nav_home;
        }
    }

    /** 当前 Tab 的 Fragment 实现了 KeyboardTouchDelegate 时，由它决定这次按下是否收起键盘 */
    @Override
    protected boolean shouldHideKeyboardOnTouch(@NonNull MotionEvent ev) {
        Integer tab = mainViewModel == null ? null : mainViewModel.getCurrentTab().getValue();
        if (tab != null) {
            Fragment fragment = getSupportFragmentManager().findFragmentByTag(TAGS[tab]);
            if (fragment instanceof KeyboardTouchDelegate && fragment.isVisible()) {
                return ((KeyboardTouchDelegate) fragment).shouldHideKeyboardOnTouch(ev);
            }
        }
        return super.shouldHideKeyboardOnTouch(ev);
    }

    /** 当前 Tab 的 Fragment 自己提供锚点时（如 AI 问答页的输入栏）显示在它上方，否则显示在底部导航上方 */
    @Nullable
    @Override
    public View getSnackbarAnchor() {
        if (binding == null) {
            return null;
        }
        Integer tab = mainViewModel == null ? null : mainViewModel.getCurrentTab().getValue();
        if (tab != null) {
            Fragment fragment = getSupportFragmentManager().findFragmentByTag(TAGS[tab]);
            if (fragment instanceof SnackbarAnchorProvider && fragment.isVisible()) {
                View anchor = ((SnackbarAnchorProvider) fragment).getSnackbarAnchor();
                if (anchor != null && anchor.isShown()) {
                    return anchor;
                }
            }
        }
        return binding.mainBottomNav;
    }
}
