package com.heban.pestai.ai.history;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.heban.pestai.ai.R;
import com.heban.pestai.ai.databinding.ItemAiSessionBinding;
import com.heban.pestai.ai.databinding.ItemAiSessionFooterBinding;
import com.heban.pestai.ai.databinding.ItemAiSessionHeaderBinding;
import com.heban.pestai.base.util.DimenUtils;

/**
 * Q03 历史对话列表：分组标题 / 对话（分组圆角卡片，组内间隔 2dp）/ 底部加载提示 3 种列表项。
 * 点击、长按（删除）与底部重试回调给 ChatHistoryActivity。
 */
final class ChatHistoryAdapter extends ListAdapter<SessionRow, ChatHistoryAdapter.Holder> {

    interface Callback {
        void onSessionClick(@NonNull SessionRow row);

        /** 长按（或无障碍「删除」操作）：确认后删除 */
        void onSessionLongClick(@NonNull SessionRow row);

        void onFooterClick(@NonNull SessionRow row);
    }

    private static final DiffUtil.ItemCallback<SessionRow> DIFF = new DiffUtil.ItemCallback<SessionRow>() {
        @Override
        public boolean areItemsTheSame(@NonNull SessionRow oldItem, @NonNull SessionRow newItem) {
            return oldItem.getKey().equals(newItem.getKey());
        }

        @Override
        public boolean areContentsTheSame(@NonNull SessionRow oldItem, @NonNull SessionRow newItem) {
            return oldItem.equals(newItem);
        }
    };

    private final Callback callback;

    ChatHistoryAdapter(@NonNull Callback callback) {
        super(DIFF);
        this.callback = callback;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).getType();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case SessionRow.TYPE_HEADER:
                return new Holder(ItemAiSessionHeaderBinding.inflate(inflater, parent, false));
            case SessionRow.TYPE_FOOTER: {
                Holder holder = new Holder(ItemAiSessionFooterBinding.inflate(inflater, parent, false));
                holder.itemView.setOnClickListener(v -> {
                    SessionRow row = rowAt(holder);
                    if (row != null) {
                        callback.onFooterClick(row);
                    }
                });
                return holder;
            }
            case SessionRow.TYPE_SESSION:
            default: {
                Holder holder = new Holder(ItemAiSessionBinding.inflate(inflater, parent, false));
                holder.itemView.setOnClickListener(v -> {
                    SessionRow row = rowAt(holder);
                    if (row != null) {
                        callback.onSessionClick(row);
                    }
                });
                holder.itemView.setOnLongClickListener(v -> {
                    SessionRow row = rowAt(holder);
                    if (row != null) {
                        callback.onSessionLongClick(row);
                    }
                    return row != null;
                });
                // 无障碍：提供「删除」自定义操作
                ViewCompat.addAccessibilityAction(holder.itemView,
                        holder.itemView.getContext().getString(R.string.ai_history_delete), (view, args) -> {
                            SessionRow row = rowAt(holder);
                            if (row != null) {
                                callback.onSessionLongClick(row);
                            }
                            return row != null;
                        });
                return holder;
            }
        }
    }

    @Nullable
    private SessionRow rowAt(@NonNull Holder holder) {
        int position = holder.getBindingAdapterPosition();
        if (position == RecyclerView.NO_POSITION || position >= getItemCount()) {
            return null;
        }
        return getItem(position);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SessionRow row = getItem(position);
        ViewBinding binding = holder.binding;
        if (binding instanceof ItemAiSessionHeaderBinding) {
            ItemAiSessionHeaderBinding b = (ItemAiSessionHeaderBinding) binding;
            b.aiSessionHeader.setText(row.getTitle());
            int top = DimenUtils.dp(b.getRoot().getContext(), row.isFirst() ? 8 : 16);
            View v = b.getRoot();
            v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
        } else if (binding instanceof ItemAiSessionBinding) {
            ItemAiSessionBinding b = (ItemAiSessionBinding) binding;
            b.aiSessionTitle.setText(row.getTitle());
            b.aiSessionPreview.setText(row.getPreview());
            b.aiSessionPreview.setVisibility(row.getPreview().isEmpty() ? View.GONE : View.VISIBLE);
            b.aiSessionTime.setText(row.getTime());
            b.aiSessionTag.setVisibility(row.isFollowUp() ? View.VISIBLE : View.GONE);
            b.getRoot().setBackgroundResource(backgroundOf(row));
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) b.getRoot().getLayoutParams();
            int top = row.isFirst() ? 0 : b.getRoot().getResources()
                    .getDimensionPixelSize(com.heban.pestai.base.R.dimen.pest_group_gap);
            if (lp.topMargin != top) {
                lp.topMargin = top;
                b.getRoot().setLayoutParams(lp);
            }
        } else if (binding instanceof ItemAiSessionFooterBinding) {
            ItemAiSessionFooterBinding b = (ItemAiSessionFooterBinding) binding;
            boolean failed = row.getFooterState() == SessionRow.FOOTER_FAILED;
            b.aiSessionFooterProgress.setVisibility(failed ? View.GONE : View.VISIBLE);
            b.aiSessionFooterIcon.setVisibility(failed ? View.VISIBLE : View.GONE);
            b.aiSessionFooterText.setText(failed ? R.string.ai_history_load_more_failed
                    : R.string.ai_history_loading_more);
            b.getRoot().setClickable(failed);
        }
    }

    /** 分组圆角：单独一项 28dp；首项上 28dp；末项下 28dp；中间 4dp（与其他列表一致） */
    private static int backgroundOf(@NonNull SessionRow row) {
        if (row.isFirst() && row.isLast()) {
            return R.drawable.ai_bg_session_single;
        }
        if (row.isFirst()) {
            return R.drawable.ai_bg_session_first;
        }
        if (row.isLast()) {
            return R.drawable.ai_bg_session_last;
        }
        return R.drawable.ai_bg_session_middle;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ViewBinding binding;

        Holder(@NonNull ViewBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
