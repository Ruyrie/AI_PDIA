package com.heban.pestai.account.ui.onboarding;

import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.heban.pestai.account.R;
import com.heban.pestai.account.databinding.ActivityOnboardingBinding;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.common.router.RouterPath;

import java.util.List;

/**
 * A02 引导页：ViewPager2 三屏横向分页（跟手滑动、松手吸附），页码与按钮文字由 OnboardingViewModel 驱动。
 * 「开始使用」进入 A05 注册，「跳过」「已有账号？登录」进入 A03；离开后 finish，按返回键直接退出应用。
 */
public class OnboardingActivity extends BaseActivity {

    private ActivityOnboardingBinding binding;
    private OnboardingViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_onboarding);
        InsetsHelper.applySystemBars(binding.getRoot());
        viewModel = obtainViewModel(OnboardingViewModel.class);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);

        OnboardingPagerAdapter adapter = new OnboardingPagerAdapter();
        ViewPager2 pager = binding.onboardingPager;
        pager.setAdapter(adapter);
        pager.setOffscreenPageLimit(1);
        View inner = pager.getChildAt(0);
        if (inner instanceof RecyclerView) {
            // 浮动标签的阴影不被裁切；去掉边缘拉伸效果
            ((RecyclerView) inner).setClipChildren(false);
            inner.setOverScrollMode(View.OVER_SCROLL_NEVER);
        }
        // 三屏是本地常量，直接提交，保证下面 setCurrentItem() 时已有数据
        List<OnboardingPage> pages = viewModel.getPages().getValue();
        if (pages != null) {
            adapter.submit(pages);
        }

        // 旋转恢复：停留在原页（不开启平滑滚动）
        Integer current = viewModel.getCurrentPage().getValue();
        pager.setCurrentItem(current == null ? 0 : current, false);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                viewModel.onPageSelected(position);
            }
        });

        viewModel.getEvent().observe(this, this::onEvent);
    }

    private void onEvent(@NonNull OnboardingViewModel.Event event) {
        switch (event) {
            case NEXT_PAGE:
                binding.onboardingPager.setCurrentItem(binding.onboardingPager.getCurrentItem() + 1, true);
                break;
            case GO_REGISTER:
                leaveTo(RouterPath.REGISTER);
                break;
            case GO_LOGIN:
            default:
                leaveTo(RouterPath.LOGIN);
                break;
        }
    }

    private void leaveTo(@NonNull String action) {
        Intent intent = RouterPath.intent(this, action);
        ActivityOptions options = ActivityOptions.makeCustomAnimation(this,
                com.heban.pestai.base.R.anim.pest_slide_in_right, com.heban.pestai.base.R.anim.pest_slide_out_left);
        startActivity(intent, options.toBundle());
        finish();
    }
}
