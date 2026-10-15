package com.heban.pestai.recognize.ui.result;

import android.content.Context;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.databinding.ItemScanOtherBinding;
import com.heban.pestai.recognize.model.OtherItem;
import com.heban.pestai.recognize.util.Formats;

import java.util.List;
import java.util.Objects;

/**
 * R01「其他可能」列表：每行显示名称、小进度条、百分比；有判断依据（reason / difference）的行可点按展开。
 * <ul>
 *     <li>DiffUtil 以 name 判断是否为同一项，以 OtherItem.equals（含 reason、difference、展开状态）判断内容是否相同；</li>
 *     <li>展开状态保存在 ResultViewModel 中：点按只回调 {@link OnToggleListener}，由 ViewModel 生成新列表后再提交；</li>
 *     <li>只有展开状态变化时，在新列表提交（DiffUtil 结果分发）之后、重新布局之前，对 transitionRoot 调用
 *     TransitionManager.beginDelayedTransition，展开区淡入、行高与下方内容同步过渡。RecyclerView 需关闭默认条目动画。</li>
 * </ul>
 */
public class OtherAdapter extends ListAdapter<OtherItem, OtherAdapter.Holder> {

    /** 点按可展开的一行 */
    public interface OnToggleListener {
        void onToggle(@Nullable String name);
    }

    private static final long EXPAND_MS = 200;

    private static final DiffUtil.ItemCallback<OtherItem> DIFF = new DiffUtil.ItemCallback<OtherItem>() {
        @Override
        public boolean areItemsTheSame(@NonNull OtherItem oldItem, @NonNull OtherItem newItem) {
            return Objects.equals(oldItem.getName(), newItem.getName());
        }

        @Override
        public boolean areContentsTheSame(@NonNull OtherItem oldItem, @NonNull OtherItem newItem) {
            return oldItem.equals(newItem);
        }
    };

    @NonNull
    private final OnToggleListener listener;
    /** 展开 / 收起过渡的场景根（页面内容区），为 null 时不做过渡 */
    @Nullable
    private final ViewGroup transitionRoot;

    public OtherAdapter(@NonNull OnToggleListener listener, @Nullable ViewGroup transitionRoot) {
        super(DIFF);
        this.listener = listener;
        this.transitionRoot = transitionRoot;
    }

    /** 提交新列表：与当前列表是同一批候选（只有展开状态变化）且页面已布局时播放过渡 */
    public void submit(@Nullable List<OtherItem> items) {
        boolean animate = transitionRoot != null && transitionRoot.isLaidOut()
                && sameCandidates(getCurrentList(), items);
        submitList(items, animate ? this::beginTransition : null);
    }

    private static boolean sameCandidates(@NonNull List<OtherItem> current, @Nullable List<OtherItem> next) {
        if (next == null || current.isEmpty() || current.size() != next.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            if (!Objects.equals(current.get(i).getName(), next.get(i).getName())) {
                return false;
            }
        }
        return true;
    }

    /** 列表更新已分发、尚未重新布局：此时记录起始状态，下一帧布局后自动过渡到新状态 */
    private void beginTransition() {
        if (transitionRoot == null || !transitionRoot.isLaidOut()) {
            return;
        }
        TransitionSet transition = new AutoTransition()
                .setOrdering(TransitionSet.ORDERING_TOGETHER)
                .setDuration(EXPAND_MS)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        TransitionManager.beginDelayedTransition(transitionRoot, transition);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Holder holder = new Holder(ItemScanOtherBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.scanOtherHeader.setOnClickListener(v -> {
            int position = holder.getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION) {
                OtherItem item = getItem(position);
                if (item.isExpandable()) {
                    listener.onToggle(item.getName());
                }
            }
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        OtherItem item = getItem(position);
        holder.binding.setItem(item);
        holder.binding.executePendingBindings();
        bindHeader(holder.binding.scanOtherHeader, item);
    }

    /**
     * 行头的点击与无障碍：可展开的行可点击、可聚焦，朗读「名称，匹配度 6%，已展开 / 已收起」，
     * 点击动作朗读为「查看判断依据 / 收起判断依据」；不可展开的行不可点击，由子 View 照常朗读。
     */
    private static void bindHeader(@NonNull View header, @NonNull OtherItem item) {
        boolean expandable = item.isExpandable();
        header.setClickable(expandable);
        header.setFocusable(expandable);
        if (!expandable) {
            header.setContentDescription(null);
            ViewCompat.replaceAccessibilityAction(header, AccessibilityActionCompat.ACTION_CLICK, null, null);
            return;
        }
        Context context = header.getContext();
        String state = context.getString(item.isExpanded()
                ? R.string.scan_result_other_expanded : R.string.scan_result_other_collapsed);
        header.setContentDescription(context.getString(R.string.scan_result_other_desc,
                item.getName(), Formats.percentText(item.getConfidence()), state));
        ViewCompat.replaceAccessibilityAction(header, AccessibilityActionCompat.ACTION_CLICK,
                context.getString(item.isExpanded()
                        ? R.string.scan_result_other_action_collapse : R.string.scan_result_other_action_expand),
                null);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemScanOtherBinding binding;

        Holder(@NonNull ItemScanOtherBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
