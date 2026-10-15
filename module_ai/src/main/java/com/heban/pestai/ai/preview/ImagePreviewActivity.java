package com.heban.pestai.ai.preview;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.heban.pestai.ai.R;
import com.heban.pestai.ai.databinding.ActivityAiImagePreviewBinding;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 图片全屏预览（module_ai 内部，显式 Intent，见 {@link #intent(Context, List, int)}）：
 * Q01 / Q02 输入栏中已上传的图片、Q02 消息中的图片。
 * <ul>
 *     <li>黑底 Edge-to-Edge，系统栏白色图标（Theme.Pest.Camera）；顶部栏避开状态栏：关闭 ✕ 与「3/9」计数（只有 1 张时隐藏）；</li>
 *     <li>ViewPager2 左右翻页，每页 {@link ZoomImageView}：双指缩放、双击放大 / 还原、放大后拖动；翻页后其他页恢复 1 倍；</li>
 *     <li>返回键或 ✕ 关闭；旋转屏幕后 ViewPager2 自动恢复当前页。</li>
 * </ul>
 */
public class ImagePreviewActivity extends BaseActivity {

    /** ArrayList&lt;String&gt;：图片源（本地 Uri 或服务器地址），按顺序 */
    public static final String EXTRA_SOURCES = "ai_preview_sources";
    /** int：先显示第几张（从 0 开始） */
    public static final String EXTRA_INDEX = "ai_preview_index";

    private ActivityAiImagePreviewBinding binding;
    private List<String> sources = Collections.emptyList();
    private final ViewPager2.OnPageChangeCallback pageCallback = new ViewPager2.OnPageChangeCallback() {
        @Override
        public void onPageSelected(int position) {
            updateCounter(position);
            resetOtherPages(position);
        }
    };

    /**
     * @param sources 图片源（本地 Uri 或服务器地址）
     * @param index   先显示第几张（从 0 开始，越界时取最近的一张）
     */
    @NonNull
    public static Intent intent(@NonNull Context context, @NonNull List<String> sources, int index) {
        return new Intent(context, ImagePreviewActivity.class)
                .putStringArrayListExtra(EXTRA_SOURCES, new ArrayList<>(sources))
                .putExtra(EXTRA_INDEX, index);
    }

    @Override
    protected boolean useDarkSystemBars() {
        return true;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAiImagePreviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsHelper.apply(binding.aiPreviewTopBar, true, false, false);

        List<String> list = getIntent().getStringArrayListExtra(EXTRA_SOURCES);
        if (list == null || list.isEmpty()) {
            finish();
            return;
        }
        sources = Collections.unmodifiableList(list);
        int index = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_INDEX, 0), sources.size() - 1));

        // 按 2 倍屏幕尺寸解码：放大到 2 倍以内仍清晰
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxSide = Math.max(metrics.widthPixels, metrics.heightPixels) * 2;
        ViewPager2 pager = binding.aiPreviewPager;
        pager.setAdapter(new PreviewAdapter(Glide.with(this), sources, maxSide, maxSide));
        if (savedInstanceState == null) {
            pager.setCurrentItem(index, false);
        }
        pager.registerOnPageChangeCallback(pageCallback);
        updateCounter(pager.getCurrentItem());

        binding.aiPreviewClose.setOnClickListener(v -> finish());
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        // ViewPager2 在这里恢复当前页（不回调 onPageSelected）
        if (binding != null && !sources.isEmpty()) {
            updateCounter(binding.aiPreviewPager.getCurrentItem());
        }
    }

    private void updateCounter(int position) {
        int total = sources.size();
        binding.aiPreviewCounter.setVisibility(total > 1 ? View.VISIBLE : View.GONE);
        binding.aiPreviewCounter.setText(getString(R.string.ai_preview_counter, position + 1, total));
    }

    /** 翻页后其他页面恢复 1 倍 */
    private void resetOtherPages(int position) {
        View child = binding.aiPreviewPager.getChildAt(0);
        if (!(child instanceof RecyclerView)) {
            return;
        }
        RecyclerView list = (RecyclerView) child;
        for (int i = 0; i < list.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = list.getChildViewHolder(list.getChildAt(i));
            if (holder instanceof PreviewAdapter.PageHolder && holder.getBindingAdapterPosition() != position) {
                ((PreviewAdapter.PageHolder) holder).resetZoom();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (binding != null) {
            binding.aiPreviewPager.unregisterOnPageChangeCallback(pageCallback);
        }
        super.onDestroy();
    }
}
