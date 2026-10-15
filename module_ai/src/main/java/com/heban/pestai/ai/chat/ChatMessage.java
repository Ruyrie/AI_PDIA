package com.heban.pestai.ai.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.ai.util.ThinkingFormatter;
import com.heban.pestai.common.constant.ChatRole;
import com.heban.pestai.common.vo.RecordItemVO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Q02 列表的界面消息模型（不可变，修改时返回新对象，便于 DiffUtil 比较）。
 * role、content、imageUrls、createdAt、reasoning（reasoning_content）、reasoningMs 与 ChatMessageVO 一致；
 * 另加只在前端使用的状态：localId（DiffUtil 判断同一条）、localUris（本次发送图片的本地 Uri，用于显示）、quote（引用卡）、
 * streaming、notice、latest、startedAt（发送时刻，思考计时）、reasoningExpanded（思考区是否展开）。
 * <p>一条用户消息的全部图片是<b>一个</b>列表项（TYPE_USER_IMAGE，九宫格显示），排在文字气泡之前；
 * 图片在输入栏中上传完成后才发送，列表中的图片没有上传状态。</p>
 * <p>content / reasoning 是<b>已收到</b>的全部文字；逐字显示到哪里由 {@link TypingTracker} 记录。</p>
 */
public final class ChatMessage {

    // ---------- 列表项类型（同时作为 RecyclerView 的 viewType） ----------
    public static final int TYPE_QUOTE = 0;
    public static final int TYPE_USER_TEXT = 1;
    public static final int TYPE_USER_IMAGE = 2;
    public static final int TYPE_AI = 3;

    // ---------- AI 气泡末尾的提示 ----------
    public static final int NOTICE_NONE = 0;
    /** 回答中断，点按重试（断网、超时、没收到 [DONE]） */
    public static final int NOTICE_BROKEN = 1;
    /** AI 服务暂不可用，请稍后再试（50201），可点按重试 */
    public static final int NOTICE_UNAVAILABLE = 2;
    /** AI 暂时没有给出回答，请换个问法试试（[DONE] 但内容为空） */
    public static final int NOTICE_EMPTY = 3;

    private static final AtomicLong IDS = new AtomicLong();

    private final long localId;
    private final int type;
    private final String role;
    @NonNull
    private final String content;
    /** 用户图片项：服务器图片地址（按发送顺序）；其他类型为空列表 */
    @NonNull
    private final List<String> imageUrls;
    /** 与 imageUrls 一一对应的本地 Uri（本次发送的图片）；历史消息为空列表 */
    @NonNull
    private final List<String> localUris;
    private final Date createdAt;
    @Nullable
    private final RecordItemVO quote;
    private final boolean streaming;
    private final int notice;
    /** 是否为列表最后一条 AI 回答（只有它显示「重新生成」） */
    private final boolean latest;
    /** 思考过程全文（已收到的部分），没有为空串 */
    @NonNull
    private final String reasoning;
    /** 思考耗时（毫秒）：从发送到收到第一个正文片段；历史消息取 reasoning_ms；未知为 null */
    @Nullable
    private final Long reasoningMs;
    /** 发送时刻（SystemClock.elapsedRealtime），「已用 N 秒」从此计时；历史消息为 0 */
    private final long startedAt;
    /** 思考区折叠后用户是否点开了 */
    private final boolean reasoningExpanded;

    private ChatMessage(long localId, int type, String role, @NonNull String content, @NonNull List<String> imageUrls,
                        @NonNull List<String> localUris, Date createdAt, @Nullable RecordItemVO quote,
                        boolean streaming, int notice, boolean latest, @NonNull String reasoning,
                        @Nullable Long reasoningMs, long startedAt, boolean reasoningExpanded) {
        this.localId = localId;
        this.type = type;
        this.role = role;
        this.content = content;
        this.imageUrls = imageUrls;
        this.localUris = localUris;
        this.createdAt = createdAt;
        this.quote = quote;
        this.streaming = streaming;
        this.notice = notice;
        this.latest = latest;
        this.reasoning = reasoning;
        this.reasoningMs = reasoningMs;
        this.startedAt = startedAt;
        this.reasoningExpanded = reasoningExpanded;
    }

    @NonNull
    public static ChatMessage quote(@NonNull RecordItemVO record) {
        return new ChatMessage(IDS.incrementAndGet(), TYPE_QUOTE, ChatRole.USER, "", Collections.emptyList(),
                Collections.emptyList(), new Date(), record, false, NOTICE_NONE, false, "", null, 0, false);
    }

    @NonNull
    public static ChatMessage userText(@NonNull String text) {
        return userText(text, new Date());
    }

    @NonNull
    public static ChatMessage userText(@NonNull String text, @Nullable Date createdAt) {
        return new ChatMessage(IDS.incrementAndGet(), TYPE_USER_TEXT, ChatRole.USER, text, Collections.emptyList(),
                Collections.emptyList(), createdAt, null, false, NOTICE_NONE, false, "", null, 0, false);
    }

    /**
     * 一条用户消息的全部图片（一个列表项）。
     *
     * @param localUris 与 imageUrls 一一对应的本地 Uri（本次发送时有，优先显示）；历史消息传空列表，
     *                  长度不一致时忽略本地 Uri
     * @param imageUrls 服务器图片地址（按发送顺序）
     */
    @NonNull
    public static ChatMessage userImages(@Nullable List<String> localUris, @NonNull List<String> imageUrls,
                                         @Nullable Date createdAt) {
        List<String> urls = immutable(imageUrls);
        List<String> uris = immutable(localUris);
        return new ChatMessage(IDS.incrementAndGet(), TYPE_USER_IMAGE, ChatRole.USER, "", urls,
                uris.size() == urls.size() ? uris : Collections.<String>emptyList(), createdAt, null, false,
                NOTICE_NONE, false, "", null, 0, false);
    }

    /**
     * 等待回答的 AI 气泡：思考区显示「正在思考… 已用 N 秒」。
     *
     * @param startedAt 发送时刻（SystemClock.elapsedRealtime）
     */
    @NonNull
    public static ChatMessage aiPending(long startedAt) {
        return new ChatMessage(IDS.incrementAndGet(), TYPE_AI, ChatRole.ASSISTANT, "", Collections.emptyList(),
                Collections.emptyList(), new Date(), null, true, NOTICE_NONE, false, "", null, startedAt, false);
    }

    /** 历史对话中的 AI 回答：有思考内容时显示折叠的「已思考 N 秒」 */
    @NonNull
    public static ChatMessage aiHistory(@Nullable String content, @Nullable String reasoning,
                                        @Nullable Integer reasoningMs, @Nullable Date createdAt) {
        return new ChatMessage(IDS.incrementAndGet(), TYPE_AI, ChatRole.ASSISTANT, content == null ? "" : content,
                Collections.emptyList(), Collections.emptyList(), createdAt, null, false, NOTICE_NONE, false,
                reasoning == null ? "" : reasoning, reasoningMs == null ? null : reasoningMs.longValue(), 0, false);
    }

    // ---------- 修改：返回新对象 ----------

    @NonNull
    public ChatMessage withContent(@NonNull String newContent) {
        return new ChatMessage(localId, type, role, newContent, imageUrls, localUris, createdAt, quote, streaming,
                notice, latest, reasoning, reasoningMs, startedAt, reasoningExpanded);
    }

    @NonNull
    public ChatMessage withReasoning(@NonNull String newReasoning) {
        return new ChatMessage(localId, type, role, content, imageUrls, localUris, createdAt, quote, streaming,
                notice, latest, newReasoning, reasoningMs, startedAt, reasoningExpanded);
    }

    @NonNull
    public ChatMessage withReasoningMs(@Nullable Long newReasoningMs) {
        return new ChatMessage(localId, type, role, content, imageUrls, localUris, createdAt, quote, streaming,
                notice, latest, reasoning, newReasoningMs, startedAt, reasoningExpanded);
    }

    @NonNull
    public ChatMessage withReasoningExpanded(boolean expanded) {
        return new ChatMessage(localId, type, role, content, imageUrls, localUris, createdAt, quote, streaming,
                notice, latest, reasoning, reasoningMs, startedAt, expanded);
    }

    /** 结束生成（或出错）：streaming 改为 false，并设置末尾提示 */
    @NonNull
    public ChatMessage finish(int newNotice) {
        return new ChatMessage(localId, type, role, content, imageUrls, localUris, createdAt, quote, false,
                newNotice, latest, reasoning, reasoningMs, startedAt, reasoningExpanded);
    }

    @NonNull
    public ChatMessage withLatest(boolean newLatest) {
        return new ChatMessage(localId, type, role, content, imageUrls, localUris, createdAt, quote, streaming,
                notice, newLatest, reasoning, reasoningMs, startedAt, reasoningExpanded);
    }

    // ---------- 读取 ----------

    public long getLocalId() {
        return localId;
    }

    public int getType() {
        return type;
    }

    public String getRole() {
        return role;
    }

    @NonNull
    public String getContent() {
        return content;
    }

    /** 服务器图片地址（按发送顺序），非图片项为空列表 */
    @NonNull
    public List<String> getImageUrls() {
        return imageUrls;
    }

    /** 本次发送图片的本地 Uri（与 imageUrls 一一对应），历史消息为空列表 */
    @NonNull
    public List<String> getLocalUris() {
        return localUris;
    }

    /** 显示用的图片源：有本地 Uri 时用本地图片（发送后不重新加载、不闪白），否则用服务器地址 */
    @NonNull
    public List<String> getImageSources() {
        if (localUris.isEmpty()) {
            return imageUrls;
        }
        List<String> result = new ArrayList<>(imageUrls.size());
        for (int i = 0; i < imageUrls.size(); i++) {
            String local = localUris.get(i);
            result.add(local != null && !local.isEmpty() ? local : imageUrls.get(i));
        }
        return Collections.unmodifiableList(result);
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    @Nullable
    public RecordItemVO getQuote() {
        return quote;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public int getNotice() {
        return notice;
    }

    public boolean isLatest() {
        return latest;
    }

    @NonNull
    public String getReasoning() {
        return reasoning;
    }

    @Nullable
    public Long getReasoningMs() {
        return reasoningMs;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public boolean isReasoningExpanded() {
        return reasoningExpanded;
    }

    public boolean hasContent() {
        return !content.trim().isEmpty();
    }

    public boolean hasReasoning() {
        return !reasoning.trim().isEmpty();
    }

    /** 思考区状态（见 {@link ThinkingFormatter}）；非 AI 消息为 HIDDEN */
    public int thinkingState() {
        if (type != TYPE_AI) {
            return ThinkingFormatter.HIDDEN;
        }
        return ThinkingFormatter.state(streaming, hasContent(), hasReasoning());
    }

    /** 思考内容当前是否展开：思考中始终展开，折叠后按用户点击 */
    public boolean isReasoningShown() {
        int state = thinkingState();
        return state == ThinkingFormatter.THINKING ? hasReasoning()
                : ThinkingFormatter.isCollapsible(state) && reasoningExpanded;
    }

    /** 回答中断 / AI 服务不可用：点按气泡重试 */
    public boolean isRetryable() {
        return type == TYPE_AI && !streaming && (notice == NOTICE_BROKEN || notice == NOTICE_UNAVAILABLE);
    }

    /** 已完成的回答下方显示操作栏（复制 / 重新生成）；生成中、出错时不显示 */
    public boolean showActions() {
        return type == TYPE_AI && !streaming && (notice == NOTICE_NONE || notice == NOTICE_EMPTY);
    }

    @NonNull
    private static List<String> immutable(@Nullable List<String> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ChatMessage that = (ChatMessage) o;
        return localId == that.localId
                && type == that.type
                && streaming == that.streaming
                && notice == that.notice
                && latest == that.latest
                && startedAt == that.startedAt
                && reasoningExpanded == that.reasoningExpanded
                && Objects.equals(role, that.role)
                && content.equals(that.content)
                && reasoning.equals(that.reasoning)
                && Objects.equals(reasoningMs, that.reasoningMs)
                && imageUrls.equals(that.imageUrls)
                && localUris.equals(that.localUris)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(quote, that.quote);
    }

    @Override
    public int hashCode() {
        return Objects.hash(localId, type, role, content, imageUrls, localUris, createdAt, quote, streaming, notice,
                latest, reasoning, reasoningMs, startedAt, reasoningExpanded);
    }

    @NonNull
    @Override
    public String toString() {
        return "ChatMessage{" + localId + ", type=" + type + ", content='" + content + "', reasoning='" + reasoning
                + "', images=" + imageUrls.size() + ", streaming=" + streaming + ", notice=" + notice
                + ", latest=" + latest + '}';
    }
}
