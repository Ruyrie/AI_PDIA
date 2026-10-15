package com.heban.pestai.ai.chat;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.heban.pestai.ai.R;
import com.heban.pestai.ai.databinding.ItemAiAnswerBinding;
import com.heban.pestai.ai.databinding.ItemAiQuoteBinding;
import com.heban.pestai.ai.databinding.ItemAiUserImageBinding;
import com.heban.pestai.ai.databinding.ItemAiUserTextBinding;
import com.heban.pestai.ai.util.AiFormat;
import com.heban.pestai.ai.util.AnswerFormatter;
import com.heban.pestai.ai.util.ThinkingFormatter;
import com.heban.pestai.base.util.DimenUtils;
import com.heban.pestai.base.util.RxClick;
import com.heban.pestai.common.vo.RecordItemVO;

import java.util.List;
import java.util.Objects;

/**
 * Q02 消息列表：引用卡 / 用户文字 / 用户图片（一条消息的全部图片为一项，{@link ImageGridView} 九宫格）/ AI 回答
 * 4 种列表项（ViewBinding）。
 * 同一条消息只是文字或状态变化时 DiffUtil 返回 {@link #PAYLOAD_CONTENT}，AI 回答只刷新气泡内容（思考区、正文、提示、操作栏），
 * 头像和图片不重新绑定。
 * <p>AI 回答的文字按 {@link TypingTracker} 的进度只显示已「打出」的部分（Markdown 渲染作用在这部分上），
 * 最新几个字带淡入透明度；ChatActivity 逐帧调用 {@link AiHolder#renderTyping(long)} 只刷新正在输出的气泡。</p>
 */
final class ChatAdapter extends ListAdapter<ChatMessage, RecyclerView.ViewHolder> {

    static final Object PAYLOAD_CONTENT = new Object();

    /** 思考区展开 / 收起的高度动画时长 */
    private static final long EXPAND_MS = 200;
    /** 思考中图标转一圈、流光扫过一次的时长 */
    private static final long THINKING_CYCLE_MS = 1500;

    interface Listener {
        void onQuoteClick(@NonNull ChatMessage message);

        /** 点按用户图片九宫格中的第 index 张 */
        void onImageClick(@NonNull ChatMessage message, int index);

        void onCopy(@NonNull ChatMessage message);

        void onRegenerate();

        void onRetry();

        void onToggleReasoning(@NonNull ChatMessage message);
    }

    private static final DiffUtil.ItemCallback<ChatMessage> DIFF = new DiffUtil.ItemCallback<ChatMessage>() {
        @Override
        public boolean areItemsTheSame(@NonNull ChatMessage oldItem, @NonNull ChatMessage newItem) {
            return oldItem.getLocalId() == newItem.getLocalId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull ChatMessage oldItem, @NonNull ChatMessage newItem) {
            return oldItem.equals(newItem);
        }

        @Nullable
        @Override
        public Object getChangePayload(@NonNull ChatMessage oldItem, @NonNull ChatMessage newItem) {
            return PAYLOAD_CONTENT;
        }
    };

    private final RequestManager glide;
    private final Listener listener;
    private final AnswerFormatter.Style answerStyle;
    private final TypingTracker typing;

    ChatAdapter(@NonNull RequestManager glide, @NonNull AnswerFormatter.Style answerStyle,
                @NonNull TypingTracker typing, @NonNull Listener listener) {
        super(DIFF);
        this.glide = glide;
        this.answerStyle = answerStyle;
        this.typing = typing;
        this.listener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).getType();
    }

    /** 列表项类型（越界返回 -1），供 ItemDecoration 计算间距 */
    int typeAt(int position) {
        List<ChatMessage> list = getCurrentList();
        return position >= 0 && position < list.size() ? list.get(position).getType() : -1;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case ChatMessage.TYPE_QUOTE:
                return new QuoteHolder(ItemAiQuoteBinding.inflate(inflater, parent, false));
            case ChatMessage.TYPE_USER_TEXT:
                return new UserTextHolder(ItemAiUserTextBinding.inflate(inflater, parent, false));
            case ChatMessage.TYPE_USER_IMAGE:
                return new ImageHolder(ItemAiUserImageBinding.inflate(inflater, parent, false));
            case ChatMessage.TYPE_AI:
            default:
                return new AiHolder(ItemAiAnswerBinding.inflate(inflater, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage message = getItem(position);
        if (holder instanceof AiHolder) {
            ((AiHolder) holder).bindContent(message);
        } else if (holder instanceof ImageHolder) {
            ((ImageHolder) holder).bind(message);
        } else if (holder instanceof UserTextHolder) {
            ((UserTextHolder) holder).bind(message);
        } else if (holder instanceof QuoteHolder) {
            ((QuoteHolder) holder).bind(message);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        // AI 回答与图片的 bind 本身就是局部刷新（只更新文字 / 状态，每格图片源不变时不重新加载）
        onBindViewHolder(holder, position);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof AiHolder) {
            ((AiHolder) holder).stopAnimations();
        }
    }

    @Override
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof AiHolder) {
            ((AiHolder) holder).resumeThinking();
        }
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof AiHolder) {
            ((AiHolder) holder).stopAnimations();
        }
    }

    // ==================== ViewHolder ====================

    final class QuoteHolder extends RecyclerView.ViewHolder {
        private final ItemAiQuoteBinding binding;
        private final int radius;
        @Nullable
        private ChatMessage message;
        @Nullable
        private Object boundSource;

        QuoteHolder(@NonNull ItemAiQuoteBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            this.radius = DimenUtils.dp(binding.getRoot().getContext(), 10);
            RxClick.throttle(binding.aiQuoteCard, v -> {
                if (message != null) {
                    listener.onQuoteClick(message);
                }
            });
        }

        @SuppressWarnings("unchecked") // Glide transform(Transformation<Bitmap>...) 的泛型可变参数
        void bind(@NonNull ChatMessage m) {
            message = m;
            RecordItemVO record = m.getQuote();
            if (record == null) {
                return;
            }
            binding.aiQuoteTitle.setText(AiFormat.quoteTitle(record));
            String source = record.getImageUrl();
            if (!Objects.equals(source, boundSource)) {
                boundSource = source;
                glide.load(source)
                        .placeholder(com.heban.pestai.base.R.drawable.pest_img_placeholder)
                        .fallback(com.heban.pestai.base.R.drawable.pest_img_placeholder)
                        .error(com.heban.pestai.base.R.drawable.pest_img_error)
                        .transform(new CenterCrop(), new RoundedCorners(radius))
                        .into(binding.aiQuoteThumb);
            }
        }
    }

    static final class UserTextHolder extends RecyclerView.ViewHolder {
        private final ItemAiUserTextBinding binding;

        UserTextHolder(@NonNull ItemAiUserTextBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull ChatMessage m) {
            binding.aiUserText.setText(m.getContent());
        }
    }

    final class ImageHolder extends RecyclerView.ViewHolder {
        private final ItemAiUserImageBinding binding;
        @Nullable
        private ChatMessage message;

        ImageHolder(@NonNull ItemAiUserImageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.aiImageGrid.setOnImageClickListener(index -> {
                if (message != null) {
                    listener.onImageClick(message, index);
                }
            });
        }

        void bind(@NonNull ChatMessage m) {
            message = m;
            // 发送后优先显示本地图片，避免切换为服务器地址时重新加载闪白；本地图片读取失败时改用服务器地址
            binding.aiImageGrid.setImages(glide, m.getImageSources(), m.getImageUrls());
        }
    }

    final class AiHolder extends RecyclerView.ViewHolder {
        private final ItemAiAnswerBinding binding;
        private final int errorColor;
        private final int noticeColor;
        private final int shimmerBase;
        private final int shimmerHighlight;
        private final int gap;
        @Nullable
        private ChatMessage message;

        /** 思考中：图标旋转 + 标题流光 + 每秒刷新「已用 N 秒」 */
        @Nullable
        private ValueAnimator thinkingAnimator;
        @Nullable
        private LinearGradient shimmer;
        private final Matrix shimmerMatrix = new Matrix();
        private long shownElapsedSecond = -1;

        /** 思考内容展开 / 收起的高度动画 */
        @Nullable
        private ValueAnimator expandAnimator;
        /** 思考内容当前（或动画目标）是否显示 */
        private boolean reasoningShown;
        private int boundExpandIcon;

        /** 上次渲染的文字长度与是否带淡入，用于跳过没有变化的帧 */
        private int renderedContent = -1;
        private int renderedReasoning = -1;
        private boolean contentFaded;
        private boolean reasoningFaded;

        AiHolder(@NonNull ItemAiAnswerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            Context context = binding.getRoot().getContext();
            errorColor = DimenUtils.themeColor(context, androidx.appcompat.R.attr.colorError);
            noticeColor = DimenUtils.themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant);
            shimmerBase = noticeColor;
            shimmerHighlight = DimenUtils.themeColor(context, androidx.appcompat.R.attr.colorPrimary);
            gap = DimenUtils.dp(context, 8);
            binding.aiAnswerCopy.setOnClickListener(v -> {
                if (message != null) {
                    listener.onCopy(message);
                }
            });
            RxClick.throttle(binding.aiAnswerRegenerate, v -> listener.onRegenerate());
            binding.aiAnswerBubble.setOnClickListener(v -> {
                if (message != null && message.isRetryable()) {
                    listener.onRetry();
                }
            });
            binding.aiAnswerThinking.setOnClickListener(v -> {
                if (message != null && ThinkingFormatter.isCollapsible(message.thinkingState())) {
                    listener.onToggleReasoning(message);
                }
            });
        }

        long localId() {
            return message == null ? -1 : message.getLocalId();
        }

        /** 完整绑定（DiffUtil / 新消息 / 逐字输出结束）：思考区、正文、末尾提示与操作栏 */
        void bindContent(@NonNull ChatMessage m) {
            boolean sameMessage = message != null && message.getLocalId() == m.getLocalId();
            message = m;
            if (!sameMessage) {
                renderedContent = -1;
                renderedReasoning = -1;
            }
            bindThinking(m, sameMessage);
            // 正在逐字输出的气泡由 ChatActivity 按帧刷新文字（不超过 30 次 / 秒），每个片段到达时这里不重复渲染
            if (!sameMessage || !typing.isTracking(m.getLocalId())) {
                render(System.nanoTime(), true);
            }

            int notice = m.getNotice();
            binding.aiAnswerNotice.setVisibility(notice == ChatMessage.NOTICE_NONE ? View.GONE : View.VISIBLE);
            if (notice != ChatMessage.NOTICE_NONE) {
                boolean error = notice != ChatMessage.NOTICE_EMPTY;
                binding.aiAnswerNoticeText.setText(noticeText(notice));
                binding.aiAnswerNoticeText.setTextColor(error ? errorColor : noticeColor);
                binding.aiAnswerNoticeIcon.setVisibility(error ? View.VISIBLE : View.GONE);
            }
            updateMargins();
            binding.aiAnswerBubble.setClickable(m.isRetryable());

            // 复制：有文字时；重新生成：只在列表最后一条回答下方；逐字显示完之后才出现
            boolean copy = m.hasContent();
            boolean regenerate = m.isLatest();
            boolean actions = m.showActions() && (copy || regenerate) && !typing.isTracking(m.getLocalId());
            binding.aiAnswerActions.setVisibility(actions ? View.VISIBLE : View.GONE);
            binding.aiAnswerCopy.setVisibility(copy ? View.VISIBLE : View.GONE);
            binding.aiAnswerRegenerate.setVisibility(regenerate ? View.VISIBLE : View.GONE);
        }

        /** 逐帧刷新：只更新正文与思考内容已显示的部分及淡入，不改其他状态 */
        void renderTyping(long nowNanos) {
            if (message != null) {
                render(nowNanos, false);
                updateMargins();
            }
        }

        // ---------- 思考区 ----------

        private void bindThinking(@NonNull ChatMessage m, boolean animate) {
            int state = m.thinkingState();
            boolean visible = state != ThinkingFormatter.HIDDEN;
            binding.aiAnswerThinking.setVisibility(visible ? View.VISIBLE : View.GONE);
            boolean thinking = state == ThinkingFormatter.THINKING;
            boolean collapsible = ThinkingFormatter.isCollapsible(state);
            setTextIfChanged(binding.aiAnswerThinkingTitle, ThinkingFormatter.title(state, m.getReasoningMs()));
            binding.aiAnswerThinkingElapsed.setVisibility(thinking && m.getStartedAt() > 0 ? View.VISIBLE : View.GONE);
            binding.aiAnswerThinkingExpand.setVisibility(collapsible ? View.VISIBLE : View.GONE);
            int expandIcon = m.isReasoningExpanded()
                    ? R.drawable.ai_ic_expand_less : com.heban.pestai.base.R.drawable.ic_expand_more;
            if (expandIcon != boundExpandIcon) {
                boundExpandIcon = expandIcon;
                binding.aiAnswerThinkingExpand.setImageResource(expandIcon);
            }
            binding.aiAnswerThinking.setClickable(collapsible);
            binding.aiAnswerThinking.setContentDescription(collapsible
                    ? binding.aiAnswerThinkingTitle.getText() + "，" + binding.getRoot().getContext().getString(
                    m.isReasoningExpanded() ? R.string.ai_reasoning_collapse : R.string.ai_reasoning_expand)
                    : null);
            if (thinking) {
                shownElapsedSecond = -1;
                updateElapsed();
                startThinking();
            } else {
                stopThinking();
            }
            setReasoningShown(m.isReasoningShown(), animate);
        }

        void resumeThinking() {
            if (message != null && message.thinkingState() == ThinkingFormatter.THINKING) {
                startThinking();
            }
        }

        private void startThinking() {
            if (thinkingAnimator != null && thinkingAnimator.isStarted()) {
                return;
            }
            if (!itemView.isAttachedToWindow()) {
                // 附着到窗口后由 onViewAttachedToWindow 启动
                return;
            }
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(THINKING_CYCLE_MS);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(a -> {
                float f = a.getAnimatedFraction();
                binding.aiAnswerThinkingIcon.setRotation(f * 360f);
                updateShimmer(f);
                updateElapsed();
            });
            thinkingAnimator = animator;
            animator.start();
        }

        private void stopThinking() {
            if (thinkingAnimator != null) {
                thinkingAnimator.cancel();
                thinkingAnimator = null;
            }
            binding.aiAnswerThinkingIcon.setRotation(0f);
            TextView title = binding.aiAnswerThinkingTitle;
            if (title.getPaint().getShader() != null) {
                title.getPaint().setShader(null);
                title.invalidate();
            }
            shimmer = null;
        }

        /** 标题流光：base → highlight → base 的线性渐变从左向右扫过 */
        private void updateShimmer(float fraction) {
            TextView title = binding.aiAnswerThinkingTitle;
            int width = title.getWidth();
            if (width <= 0) {
                return;
            }
            if (shimmer == null) {
                shimmer = new LinearGradient(0, 0, width, 0,
                        new int[]{shimmerBase, shimmerHighlight, shimmerBase}, new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP);
                title.getPaint().setShader(shimmer);
            }
            shimmerMatrix.setTranslate(-width + 2f * width * fraction, 0);
            shimmer.setLocalMatrix(shimmerMatrix);
            title.invalidate();
        }

        private void updateElapsed() {
            ChatMessage m = message;
            if (m == null || m.getStartedAt() <= 0) {
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - m.getStartedAt();
            long second = Math.max(0, elapsed / 1000);
            if (second != shownElapsedSecond) {
                shownElapsedSecond = second;
                binding.aiAnswerThinkingElapsed.setText(ThinkingFormatter.elapsed(elapsed));
            }
        }

        /** 思考内容显示 / 隐藏；同一条消息的状态变化（正文开始自动折叠、点击展开收起）用 200ms 高度动画 */
        private void setReasoningShown(boolean show, boolean animate) {
            View view = binding.aiAnswerReasoning;
            if (show == reasoningShown && expandAnimator == null) {
                view.setVisibility(show ? View.VISIBLE : View.GONE);
                return;
            }
            reasoningShown = show;
            if (expandAnimator != null) {
                expandAnimator.cancel();
                expandAnimator = null;
            }
            if (!animate || !itemView.isAttachedToWindow() || !itemView.isLaidOut()) {
                setReasoningHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
                view.setVisibility(show ? View.VISIBLE : View.GONE);
                return;
            }
            int from = view.getVisibility() == View.VISIBLE ? view.getHeight() : 0;
            int to;
            if (show) {
                view.setVisibility(View.VISIBLE);
                renderReasoning(System.nanoTime(), true);
                View parent = (View) binding.aiAnswerBubble.getParent();
                int maxWidth = parent.getWidth() - binding.aiAnswerBubble.getPaddingStart()
                        - binding.aiAnswerBubble.getPaddingEnd();
                view.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, maxWidth), View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                to = view.getMeasuredHeight();
            } else {
                to = 0;
            }
            ValueAnimator animator = ValueAnimator.ofInt(from, to);
            animator.setDuration(EXPAND_MS);
            animator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
            animator.addUpdateListener(a -> setReasoningHeight((int) a.getAnimatedValue()));
            animator.addListener(new AnimatorListenerAdapter() {
                private boolean canceled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    canceled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (expandAnimator == animation) {
                        expandAnimator = null;
                    }
                    if (!canceled) {
                        setReasoningHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
                        view.setVisibility(show ? View.VISIBLE : View.GONE);
                        updateMargins();
                    }
                }
            });
            expandAnimator = animator;
            animator.start();
        }

        private void setReasoningHeight(int height) {
            ViewGroup.LayoutParams lp = binding.aiAnswerReasoning.getLayoutParams();
            if (lp.height != height) {
                lp.height = height;
                binding.aiAnswerReasoning.setLayoutParams(lp);
            }
        }

        // ---------- 文字 ----------

        private void render(long nowNanos, boolean force) {
            renderReasoning(nowNanos, force);
            renderContent(nowNanos, force);
        }

        private void renderContent(long nowNanos, boolean force) {
            ChatMessage m = message;
            if (m == null) {
                return;
            }
            String content = m.getContent();
            int shown = AiFormat.safeEnd(content, typing.contentShown(m.getLocalId(), content.length()));
            float[] fade = typing.contentFade(m.getLocalId(), nowNanos);
            boolean faded = fade.length > 0;
            if (!force && shown == renderedContent && !faded && !contentFaded) {
                return;
            }
            renderedContent = shown;
            contentFaded = faded;
            String raw = content.substring(0, shown);
            boolean showText = !raw.trim().isEmpty();
            binding.aiAnswerContent.setVisibility(showText ? View.VISIBLE : View.GONE);
            if (showText) {
                SpannableStringBuilder sb = AnswerFormatter.format(raw, answerStyle);
                AnswerFormatter.applyFade(sb, raw, fade);
                binding.aiAnswerContent.setText(sb);
            } else {
                binding.aiAnswerContent.setText(null);
            }
        }

        private void renderReasoning(long nowNanos, boolean force) {
            ChatMessage m = message;
            if (m == null || binding.aiAnswerReasoning.getVisibility() != View.VISIBLE) {
                return;
            }
            String reasoning = m.getReasoning();
            int shown = AiFormat.safeEnd(reasoning, typing.reasoningShown(m.getLocalId(), reasoning.length()));
            float[] fade = typing.reasoningFade(m.getLocalId(), nowNanos);
            boolean faded = fade.length > 0;
            if (!force && shown == renderedReasoning && !faded && !reasoningFaded) {
                return;
            }
            renderedReasoning = shown;
            reasoningFaded = faded;
            String raw = reasoning.substring(0, shown);
            SpannableStringBuilder sb = new SpannableStringBuilder(trimNewlines(raw));
            AnswerFormatter.applyFade(sb, raw, fade);
            binding.aiAnswerReasoningText.setText(sb);
        }

        /** 思考区、思考内容、正文之间 8dp；提示与正文之间 6dp */
        private void updateMargins() {
            boolean header = binding.aiAnswerThinking.getVisibility() == View.VISIBLE;
            boolean reasoning = binding.aiAnswerReasoning.getVisibility() == View.VISIBLE;
            boolean content = binding.aiAnswerContent.getVisibility() == View.VISIBLE;
            setTopMargin(binding.aiAnswerReasoning, header ? gap : 0);
            setTopMargin(binding.aiAnswerContent, header || reasoning ? gap : 0);
            setTopMargin(binding.aiAnswerNotice, header || reasoning || content ? gap * 3 / 4 : 0);
        }

        private void setTopMargin(@NonNull View view, int top) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            if (lp.topMargin != top) {
                lp.topMargin = top;
                view.setLayoutParams(lp);
            }
        }

        private int noticeText(int notice) {
            switch (notice) {
                case ChatMessage.NOTICE_UNAVAILABLE:
                    return R.string.ai_notice_unavailable;
                case ChatMessage.NOTICE_EMPTY:
                    return R.string.ai_notice_empty;
                case ChatMessage.NOTICE_BROKEN:
                default:
                    return R.string.ai_notice_broken;
            }
        }

        /** 回收 / 离开窗口：停止思考动画与高度动画（高度直接设为最终状态），避免泄漏 */
        void stopAnimations() {
            stopThinking();
            if (expandAnimator != null) {
                expandAnimator.cancel();
                expandAnimator = null;
                setReasoningHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
                binding.aiAnswerReasoning.setVisibility(reasoningShown ? View.VISIBLE : View.GONE);
            }
        }
    }

    private static void setTextIfChanged(@NonNull TextView view, @NonNull CharSequence text) {
        if (!android.text.TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    @NonNull
    private static String trimNewlines(@NonNull String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        int start = 0;
        while (start < end && (text.charAt(start) == '\n' || text.charAt(start) == '\r')) {
            start++;
        }
        return text.substring(start, end);
    }
}
