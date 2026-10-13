package com.heban.pestai.ai.chat;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.bumptech.glide.Glide;
import com.heban.pestai.ai.R;
import com.heban.pestai.ai.databinding.ActivityChatBinding;
import com.heban.pestai.ai.util.AiLoginDialog;
import com.heban.pestai.ai.util.AnswerFormatter;
import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.SnackbarAnchorProvider;
import com.heban.pestai.base.util.DimenUtils;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.base.util.RxClick;
import com.heban.pestai.common.MainViewModel;
import com.heban.pestai.common.router.RouterPath;

import java.util.List;

/**
 * Q02 AI 对话页（RouterPath.AI_CHAT；module_ai 内部用显式 Intent，见 {@link #intent(Context, ChatArgs)}）。
 * <p>Intent 参数：RouterPath.EXTRA_RECORD_ID（long，追问识别结果，缺省 -1）、{@link #EXTRA_QUESTION}（自动发送的问题）、
 * RouterPath.EXTRA_IMAGE_URI（自动发送的图片）、{@link #EXTRA_SESSION_ID}（打开历史对话）。</p>
 * <ul>
 *     <li>根布局用 InsetsHelper 避开系统栏与软键盘，输入栏始终贴在键盘上方；</li>
 *     <li>逐字输出：Choreographer 逐帧推进 {@link TypingTracker}，刷新不超过 30 次 / 秒，只局部刷新正在输出的气泡；
 *     页面不可见时停止，销毁时移除回调；</li>
 *     <li>滚动：列表在底部（48dp 以内）时内容增长平滑跟随；用户上滑后停止跟随并在输入栏上方右侧显示「回到底部」；</li>
 *     <li>生成中按返回 / 点新对话先停止生成。</li>
 * </ul>
 */
public class ChatActivity extends BaseActivity implements SnackbarAnchorProvider {

    /** String：自动发送的问题（只在 module_ai 内部使用，不放入 RouterPath） */
    public static final String EXTRA_QUESTION = "question";
    /** long：要打开的历史对话 ID（Q03 进入，只在 module_ai 内部使用） */
    public static final String EXTRA_SESSION_ID = "session_id";

    /** 距离底部不超过 48dp 视为在底部 */
    private static final int BOTTOM_THRESHOLD_DP = 48;
    /** 逐字刷新间隔：不超过 30 次 / 秒 */
    private static final long FRAME_INTERVAL_NANOS = 1_000_000_000L / 30;
    /** 跟随滚动时长 */
    private static final int FOLLOW_MS = 200;
    /** 「回到底部」显示 / 隐藏动画 */
    private static final long BUTTON_FADE_MS = 150;

    private ActivityChatBinding binding;
    private ChatViewModel viewModel;
    private ChatAdapter adapter;
    private LinearLayoutManager layoutManager;
    private TypingTracker typing;
    private ActivityResultLauncher<PickVisualMediaRequest> pickImage;
    private int lastCount = -1;
    /** 列表还没有内容时收到「滚到底部」（打开历史对话）：等这次提交的列表生效后直接定位到最后一项，避免先显示顶部再跳 */
    private boolean jumpToBottomPending;
    @Nullable
    private Boolean showingStop;
    @Nullable
    private Animator iconAnimator;

    /** 是否跟随新内容滚动到底部（用户上滑离开底部后为 false） */
    private boolean following = true;
    /** 用户正在拖动或惯性滑动（从按下到滚动停止） */
    private boolean userScrolling;
    private int bottomThreshold;
    private final Interpolator followInterpolator = new DecelerateInterpolator();
    private boolean scrollButtonShown;

    private boolean started;
    private boolean frameScheduled;
    private long lastRenderNanos;
    private final Choreographer.FrameCallback frameCallback = this::doFrame;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::onListLayout;

    /** 创建打开 Q02 的显式 Intent（module_ai 内部使用；其他组件用 RouterPath.AI_CHAT） */
    @NonNull
    public static Intent intent(@NonNull Context context, @NonNull ChatArgs args) {
        Intent intent = new Intent(context, ChatActivity.class);
        if (args.recordId > 0) {
            intent.putExtra(RouterPath.EXTRA_RECORD_ID, args.recordId);
        }
        if (args.question != null) {
            intent.putExtra(EXTRA_QUESTION, args.question);
        }
        if (args.imageUri != null) {
            intent.putExtra(RouterPath.EXTRA_IMAGE_URI, args.imageUri);
        }
        if (args.hasSession()) {
            intent.putExtra(EXTRA_SESSION_ID, args.sessionId);
        }
        return intent;
    }

    @NonNull
    static ChatArgs readArgs(@NonNull Intent intent) {
        return new ChatArgs(
                intent.getLongExtra(RouterPath.EXTRA_RECORD_ID, RouterPath.NO_RECORD),
                intent.getStringExtra(EXTRA_QUESTION),
                intent.getStringExtra(RouterPath.EXTRA_IMAGE_URI),
                intent.getLongExtra(EXTRA_SESSION_ID, ChatArgs.NO_SESSION));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_chat);
        viewModel = obtainViewModel(ChatViewModel.class);
        typing = viewModel.getTyping();
        binding.setLifecycleOwner(this);
        binding.setVm(viewModel);
        InsetsHelper.apply(binding.chatRoot, true, true, true);
        observeToast(viewModel);
        bottomThreshold = DimenUtils.dp(this, BOTTOM_THRESHOLD_DP);

        pickImage = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                viewModel.setPendingImage(uri.toString());
            }
        });

        setupList();
        setupActions();
        observe();

        viewModel.init(readArgs(getIntent()));
    }

    private void setupList() {
        layoutManager = new LinearLayoutManager(this);
        RecyclerView list = binding.chatList;
        list.setLayoutManager(layoutManager);
        RecyclerView.ItemAnimator animator = list.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            // 关闭变更动画，否则每次刷新都会淡入淡出一次
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        list.addItemDecoration(new ChatItemDecoration(DimenUtils.dp(this, 12), DimenUtils.dp(this, 6)));
        adapter = new ChatAdapter(Glide.with(this), AnswerFormatter.Style.from(this), typing,
                new ChatAdapter.Listener() {
                    @Override
                    public void onQuoteClick(@NonNull ChatMessage message) {
                        viewModel.openQuotedRecord(message);
                    }

                    @Override
                    public void onImageClick(@NonNull ChatMessage message) {
                        viewModel.onImageClick(message);
                    }

                    @Override
                    public void onCopy(@NonNull ChatMessage message) {
                        copyAnswer(message);
                    }

                    @Override
                    public void onRegenerate() {
                        viewModel.regenerate();
                    }

                    @Override
                    public void onRetry() {
                        viewModel.retry();
                    }

                    @Override
                    public void onToggleReasoning(@NonNull ChatMessage message) {
                        viewModel.toggleReasoning(message);
                    }
                });
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userScrolling = true;
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE && userScrolling) {
                    userScrolling = false;
                    following = isNearBottom();
                }
                updateScrollButton();
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (userScrolling) {
                    // 用户上滑离开底部停止跟随；滑回底部 48dp 以内恢复跟随
                    following = isNearBottom();
                }
                updateScrollButton();
            }
        });
        // 每次布局后（正在输出的气泡变高、新消息、键盘弹出列表变矮）：跟随时平滑滚到底部
        list.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    private void setupActions() {
        binding.chatBack.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        RxClick.throttle(binding.chatNew, v -> startNewConversation());
        RxClick.throttle(binding.chatSend, v -> viewModel.onSendOrStop());
        binding.chatScrollBottom.setOnClickListener(v -> {
            following = true;
            scrollToBottom(true);
            updateScrollButton();
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 生成中先停止生成，再关闭对话页
                viewModel.stopGenerating();
                finish();
            }
        });
    }

    private void observe() {
        viewModel.getMessages().observe(this, this::submitMessages);
        viewModel.getStreaming().observe(this, streaming -> updateSendIcon(Boolean.TRUE.equals(streaming)));
        viewModel.getEvent().observe(this, this::handleEvent);
    }

    private void submitMessages(@Nullable List<ChatMessage> list) {
        int count = list == null ? 0 : list.size();
        adapter.submitList(list, () -> {
            // 首次提交（含旋转屏幕重建）或打开历史对话：在布局前定位到最后一项，不先显示顶部再跳
            if ((jumpToBottomPending || lastCount < 0) && count > 0) {
                jumpToBottomPending = false;
                layoutManager.scrollToPosition(count - 1);
            }
            if (count != lastCount) {
                // 插入引用卡、移除气泡后相邻项的间距随之变化；post 避免在滚动 / 布局过程中调用
                lastCount = count;
                binding.chatList.post(binding.chatList::invalidateItemDecorations);
            }
            updateScrollButton();
        });
        scheduleFrame();
    }

    // ==================== 逐字输出 ====================

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        // 重新可见（含旋转屏幕后重建）：下一帧重新计时，从已显示的位置继续
        typing.pause();
        lastRenderNanos = 0;
        scheduleFrame();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        cancelFrame();
        typing.pause();
    }

    private void scheduleFrame() {
        if (started && !frameScheduled && typing.isActive()) {
            frameScheduled = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private void cancelFrame() {
        if (frameScheduled) {
            frameScheduled = false;
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        }
    }

    /** 每帧回调，按 30 次 / 秒节流：推进进度并只刷新正在输出的气泡；显示完毕的气泡完整重绑一次（显示操作栏） */
    private void doFrame(long frameTimeNanos) {
        frameScheduled = false;
        if (binding == null || !started) {
            return;
        }
        if (lastRenderNanos == 0 || frameTimeNanos - lastRenderNanos >= FRAME_INTERVAL_NANOS) {
            lastRenderNanos = frameTimeNanos;
            boolean dirty = typing.step(frameTimeNanos);
            List<Long> done = typing.drainCompleted();
            if (dirty || !done.isEmpty()) {
                refreshTyping(frameTimeNanos, done);
            }
        }
        scheduleFrame();
    }

    private void refreshTyping(long nowNanos, @NonNull List<Long> done) {
        RecyclerView list = binding.chatList;
        List<ChatMessage> current = adapter.getCurrentList();
        for (int i = 0; i < list.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = list.getChildViewHolder(list.getChildAt(i));
            if (!(holder instanceof ChatAdapter.AiHolder)) {
                continue;
            }
            ChatAdapter.AiHolder ai = (ChatAdapter.AiHolder) holder;
            long id = ai.localId();
            if (done.contains(id)) {
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION && position < current.size()
                        && current.get(position).getLocalId() == id) {
                    ai.bindContent(current.get(position));
                }
            } else if (typing.isTracking(id)) {
                ai.renderTyping(nowNanos);
            }
        }
    }

    // ==================== 滚动 ====================

    /** 最后一项底边与列表可见区域底边的距离（px）；最后一项不在屏幕上返回 Integer.MAX_VALUE */
    private int distanceToBottom() {
        int count = adapter.getItemCount();
        if (count == 0) {
            return 0;
        }
        View last = layoutManager.findViewByPosition(count - 1);
        if (last == null) {
            return Integer.MAX_VALUE;
        }
        RecyclerView list = binding.chatList;
        return layoutManager.getDecoratedBottom(last) - (list.getHeight() - list.getPaddingBottom());
    }

    private boolean isNearBottom() {
        return distanceToBottom() <= bottomThreshold;
    }

    private void onListLayout() {
        if (binding == null) {
            return;
        }
        if (following && !userScrolling) {
            scrollToBottom(true);
        }
        updateScrollButton();
    }

    /** 滚到底部：最后一项不在屏幕上时先跳过去（底边对齐），否则按剩余距离滚动 */
    private void scrollToBottom(boolean smooth) {
        int count = adapter.getItemCount();
        if (count == 0) {
            return;
        }
        RecyclerView list = binding.chatList;
        if (layoutManager.findViewByPosition(count - 1) == null) {
            list.scrollToPosition(count - 1);
            return;
        }
        // 最后一项底边在可见区域下方即可继续滚动（不用 canScrollVertically：可变高度时它是估算值）
        int distance = distanceToBottom();
        if (distance <= 0) {
            return;
        }
        if (smooth) {
            list.smoothScrollBy(0, distance, followInterpolator, FOLLOW_MS);
        } else {
            list.scrollBy(0, distance);
        }
    }

    /** 不在底部（用户上滑停止跟随，且距底部超过 48dp）时显示「回到底部」 */
    private void updateScrollButton() {
        if (binding == null) {
            return;
        }
        boolean show = !following && adapter.getItemCount() > 0 && distanceToBottom() > bottomThreshold;
        if (show == scrollButtonShown) {
            return;
        }
        scrollButtonShown = show;
        View button = binding.chatScrollBottom;
        button.animate().cancel();
        if (show) {
            button.setVisibility(View.VISIBLE);
            button.setAlpha(0f);
            button.setScaleX(0.8f);
            button.setScaleY(0.8f);
            button.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(BUTTON_FADE_MS).setListener(null).start();
        } else {
            button.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(BUTTON_FADE_MS)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (!scrollButtonShown && binding != null) {
                                binding.chatScrollBottom.setVisibility(View.GONE);
                            }
                        }
                    }).start();
        }
    }

    // ==================== 事件 ====================

    private void handleEvent(@Nullable ChatEvent event) {
        if (event == null) {
            return;
        }
        switch (event.type) {
            case ChatEvent.NEED_LOGIN:
                AiLoginDialog.show(this);
                break;
            case ChatEvent.PICK_IMAGE:
                pickImage.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build());
                break;
            case ChatEvent.OPEN_RECORD:
                startActivity(RouterPath.intent(this, RouterPath.SCAN_RESULT)
                        .putExtra(RouterPath.EXTRA_RECORD_ID, event.recordId)
                        .putExtra(RouterPath.EXTRA_FROM_CHAT, true));
                break;
            case ChatEvent.SCROLL_TO_BOTTOM:
                // 发送 / 重新生成 / 打开历史对话：恢复跟随并滚到底部（即使用户之前上滑了）
                following = true;
                userScrolling = false;
                binding.chatList.stopScroll();
                if (adapter.getItemCount() == 0) {
                    jumpToBottomPending = true;
                    break;
                }
                binding.chatList.post(() -> {
                    if (binding != null) {
                        scrollToBottom(true);
                        updateScrollButton();
                    }
                });
                break;
            default:
                break;
        }
    }

    /** 新对话：停止当前生成，关闭对话页，回到主页面 AI 问答 Tab（Q01） */
    private void startNewConversation() {
        viewModel.stopGenerating();
        startActivity(RouterPath.intent(this, RouterPath.MAIN)
                .putExtra(RouterPath.EXTRA_TAB, MainViewModel.TAB_AI));
        finish();
    }

    private void copyAnswer(@NonNull ChatMessage message) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.ai_clip_label),
                AnswerFormatter.plainText(message.getContent())));
        showSnackbar(getString(R.string.ai_copied));
    }

    /** 生成中发送按钮变为停止按钮（stop 实心），图标 150ms 淡出淡入 */
    private void updateSendIcon(boolean stop) {
        if (showingStop != null && showingStop == stop) {
            return;
        }
        boolean first = showingStop == null;
        showingStop = stop;
        int icon = stop ? com.heban.pestai.base.R.drawable.ic_stop_fill
                : com.heban.pestai.base.R.drawable.ic_arrow_upward;
        binding.chatSend.setContentDescription(getString(stop ? R.string.ai_stop : R.string.ai_send));
        if (iconAnimator != null) {
            iconAnimator.cancel();
        }
        if (first) {
            binding.chatSend.setImageResource(icon);
            binding.chatSend.setImageAlpha(255);
            return;
        }
        ValueAnimator fade = ValueAnimator.ofInt(255, 0, 255);
        fade.setDuration(150);
        final boolean[] swapped = {false};
        fade.addUpdateListener(a -> {
            if (!swapped[0] && a.getAnimatedFraction() >= 0.5f) {
                swapped[0] = true;
                binding.chatSend.setImageResource(icon);
            }
            binding.chatSend.setImageAlpha((int) a.getAnimatedValue());
        });
        fade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                binding.chatSend.setImageResource(icon);
                binding.chatSend.setImageAlpha(255);
            }
        });
        iconAnimator = fade;
        fade.start();
    }

    /** 点击输入栏（含发送 / 停止按钮）与「回到底部」时保留软键盘，点击列表空白处收起 */
    @Override
    protected boolean shouldHideKeyboardOnTouch(@NonNull MotionEvent ev) {
        if (binding == null) {
            return true;
        }
        return !isTouchInside(binding.chatInputBar, ev)
                && !(binding.chatPending.getVisibility() == View.VISIBLE && isTouchInside(binding.chatPending, ev))
                && !(binding.chatScrollBottom.getVisibility() == View.VISIBLE
                && isTouchInside(binding.chatScrollBottom, ev));
    }

    @Nullable
    @Override
    public View getSnackbarAnchor() {
        return binding == null ? null : binding.chatInputBar;
    }

    @Override
    protected void onDestroy() {
        cancelFrame();
        if (iconAnimator != null) {
            iconAnimator.cancel();
            iconAnimator = null;
        }
        if (binding != null) {
            binding.chatScrollBottom.animate().cancel();
            ViewTreeObserver observer = binding.chatList.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(layoutListener);
            }
            // 解除 RecyclerView 对 Adapter / ViewHolder 的引用，回收时停止气泡内的动画
            binding.chatList.setAdapter(null);
        }
        super.onDestroy();
    }
}
