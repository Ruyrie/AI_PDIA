package com.heban.pestai.ai.history;

import android.os.Bundle;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.heban.pestai.ai.R;
import com.heban.pestai.ai.chat.ChatActivity;
import com.heban.pestai.ai.chat.ChatArgs;
import com.heban.pestai.ai.databinding.ActivityChatHistoryBinding;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.RxClick;

import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * Q03 历史对话（module_ai 内部页面，Q01 右上角进入，显式 Intent）。
 * <ul>
 *     <li>按 updated_at 分组（今天 / 昨天 / 近 7 天 / 更早）的分组圆角列表；下拉刷新；滑到底部自动加载下一页（每页 20）；</li>
 *     <li>点击打开 Q02 继续该对话（session_id）；从 Q02 返回时静默刷新；</li>
 *     <li>长按弹出「删除这条对话？」确认对话框，删除成功后移除并 Snackbar「已删除」；</li>
 *     <li>空状态「开始提问」返回 Q01；加载失败显示原因与「重试」。</li>
 * </ul>
 */
public class ChatHistoryActivity extends BaseActivity {

    /** 最后可见行距末尾不足 3 行时加载下一页 */
    private static final int LOAD_MORE_THRESHOLD = 3;

    private ActivityChatHistoryBinding binding;
    private ChatHistoryViewModel viewModel;
    private ChatHistoryAdapter adapter;
    private LinearLayoutManager layoutManager;
    /** 点击对话 500ms 防连点 */
    private final PublishSubject<Runnable> clicks = PublishSubject.create();
    @Nullable
    private Disposable clickTask;
    @Nullable
    private AlertDialog deleteDialog;
    /** 第一次 onResume 不刷新（start() 已在加载） */
    private boolean resumedOnce;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_chat_history);
        binding.setLifecycleOwner(this);
        viewModel = obtainViewModel(ChatHistoryViewModel.class);
        binding.setVm(viewModel);
        InsetsHelper.applySystemBars(binding.getRoot());
        observeToast(viewModel);

        clickTask = clicks
                .throttleFirst(RxClick.WINDOW_MS, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
                .subscribe(Runnable::run);

        binding.historyChatToolbar.setNavigationOnClickListener(v -> finish());
        // 「开始提问」：返回 Q01（本页只从 Q01 进入）
        RxClick.throttle(binding.historyChatStart, v -> finish());
        setupList();

        viewModel.getRows().observe(this, rows -> adapter.submitList(rows,
                () -> binding.historyChatList.post(this::loadMoreIfNearEnd)));
        viewModel.getOpenSession().observe(this, id -> {
            if (id != null) {
                startActivity(ChatActivity.intent(this, ChatArgs.ofSession(id)));
            }
        });
        viewModel.start();
    }

    private void setupList() {
        adapter = new ChatHistoryAdapter(new ChatHistoryAdapter.Callback() {
            @Override
            public void onSessionClick(@NonNull SessionRow row) {
                clicks.onNext(() -> viewModel.onRowClick(row));
            }

            @Override
            public void onSessionLongClick(@NonNull SessionRow row) {
                confirmDelete(row);
            }

            @Override
            public void onFooterClick(@NonNull SessionRow row) {
                if (row.getFooterState() == SessionRow.FOOTER_FAILED) {
                    viewModel.retryLoadMore();
                }
            }
        });
        layoutManager = new LinearLayoutManager(this);
        RecyclerView list = binding.historyChatList;
        list.setLayoutManager(layoutManager);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) {
                    loadMoreIfNearEnd();
                }
            }
        });
        binding.historyChatRefresh.setColorSchemeColors(
                MaterialColors.getColor(binding.historyChatRefresh, androidx.appcompat.R.attr.colorPrimary));
        binding.historyChatRefresh.setOnRefreshListener(viewModel::refresh);
    }

    private void loadMoreIfNearEnd() {
        if (binding == null) {
            return;
        }
        int count = adapter.getItemCount();
        if (count == 0) {
            return;
        }
        int last = layoutManager.findLastVisibleItemPosition();
        if (last != RecyclerView.NO_POSITION && last >= count - LOAD_MORE_THRESHOLD) {
            viewModel.loadMore();
        }
    }

    /** 「删除这条对话？」/「删除后无法恢复。」/「取消」「删除」（error 色） */
    private void confirmDelete(@NonNull SessionRow row) {
        if (deleteDialog != null && deleteDialog.isShowing()) {
            return;
        }
        long sessionId = row.getSessionId();
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ai_history_delete_title)
                .setMessage(R.string.ai_history_delete_message)
                .setNegativeButton(com.heban.pestai.base.R.string.pest_cancel, null)
                .setPositiveButton(R.string.ai_history_delete, (d, which) -> viewModel.delete(sessionId))
                .create();
        dialog.setOnShowListener(d -> {
            Button delete = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (delete != null) {
                delete.setTextColor(MaterialColors.getColor(delete, androidx.appcompat.R.attr.colorError));
            }
        });
        deleteDialog = dialog;
        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumedOnce) {
            // 从 Q02 返回：对话可能有了新消息（排到最前）或标题变化
            viewModel.refreshSilently();
        }
        resumedOnce = true;
    }

    @Override
    protected void onDestroy() {
        if (clickTask != null) {
            clickTask.dispose();
            clickTask = null;
        }
        if (deleteDialog != null) {
            deleteDialog.dismiss();
            deleteDialog = null;
        }
        super.onDestroy();
    }
}
