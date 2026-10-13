package com.heban.pestai.ai.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.ai.util.TypingPacer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Q02 正在逐字输出的 AI 回答的显示进度（纯 Java，不持有 View）。
 * <ul>
 *     <li>ChatViewModel 收到片段时更新已收到的字数（{@link #setReasoning} / {@link #setContent}），
 *     结束 / 停止 / 出错时 {@link #finish}；</li>
 *     <li>ChatActivity 用 Choreographer 逐帧（不超过 30 次 / 秒）调用 {@link #step(long)} 推进，
 *     只局部刷新正在输出的气泡；</li>
 *     <li>保存在 ViewModel 中：旋转屏幕后从已显示的位置继续，不会从头再打一遍；
 *     不在这里的消息（历史消息、早已显示完的回答）直接完整显示。</li>
 * </ul>
 * 只在主线程使用。
 */
public final class TypingTracker {

    /** 一条 AI 回答的思考内容与正文两段进度 */
    static final class Entry {
        final TypingPacer reasoning = new TypingPacer(TypingPacer.REASONING_CPS);
        final TypingPacer content = new TypingPacer(TypingPacer.CONTENT_CPS);

        boolean isDone(long nowNanos) {
            return reasoning.isFinished() && content.isFinished()
                    && reasoning.isCaughtUp() && content.isCaughtUp()
                    && !content.isFading(nowNanos) && !reasoning.isFading(nowNanos);
        }
    }

    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private final List<Long> completed = new ArrayList<>();
    /** 上一帧时刻；0 表示刚开始 / 刚恢复（下一帧不推进，避免一次跳出一大段） */
    private long lastFrameNanos;

    /** 开始一条新回答 */
    public void begin(long id) {
        entries.put(id, new Entry());
    }

    /** 已收到的思考内容字数 */
    public void setReasoning(long id, int length) {
        Entry entry = entries.get(id);
        if (entry != null) {
            entry.reasoning.setTarget(length);
        }
    }

    /** 已收到的正文字数；正文开始后思考区折叠，剩余思考内容直接显示完 */
    public void setContent(long id, int length) {
        Entry entry = entries.get(id);
        if (entry == null) {
            return;
        }
        if (length > 0 && !entry.reasoning.isCaughtUp()) {
            entry.reasoning.skipToEnd();
        }
        if (length > 0) {
            entry.reasoning.finish();
        }
        entry.content.setTarget(length);
    }

    /** 输出结束（[DONE]、停止、出错）：剩余内容在 0.3 秒内显示完 */
    public void finish(long id) {
        Entry entry = entries.get(id);
        if (entry != null) {
            entry.reasoning.finish();
            entry.content.finish();
        }
    }

    /** 回答被移除（重新生成、撤回提问） */
    public void remove(long id) {
        entries.remove(id);
    }

    public void clear() {
        entries.clear();
        completed.clear();
    }

    /** 还有回答在逐字输出（需要继续逐帧推进） */
    public boolean isActive() {
        return !entries.isEmpty();
    }

    public boolean isTracking(long id) {
        return entries.containsKey(id);
    }

    /** 页面不可见 / 重建：下一帧重新开始计时 */
    public void pause() {
        lastFrameNanos = 0;
    }

    /**
     * 推进一帧。显示完且淡入结束的回答从跟踪中移除，ID 加入 {@link #drainCompleted()}。
     *
     * @return 是否有回答需要重绘（显示字数变化、仍在淡入或刚完成）
     */
    public boolean step(long frameNanos) {
        double dt = lastFrameNanos == 0 ? 0 : (frameNanos - lastFrameNanos) / 1e9;
        lastFrameNanos = frameNanos;
        boolean dirty = false;
        Iterator<Map.Entry<Long, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Entry> e = it.next();
            Entry entry = e.getValue();
            boolean changed = entry.reasoning.step(dt, frameNanos);
            changed |= entry.content.step(dt, frameNanos);
            if (entry.isDone(frameNanos)) {
                it.remove();
                completed.add(e.getKey());
                dirty = true;
            } else if (changed || entry.content.isFading(frameNanos) || entry.reasoning.isFading(frameNanos)) {
                dirty = true;
            }
        }
        return dirty;
    }

    /** 自上次调用以来显示完毕的回答 ID（需要完整重绑一次：去掉淡入、显示操作栏） */
    @NonNull
    public List<Long> drainCompleted() {
        if (completed.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> out = new ArrayList<>(completed);
        completed.clear();
        return out;
    }

    /** 正文显示的字数；不在跟踪中返回 length（完整显示） */
    public int contentShown(long id, int length) {
        Entry entry = entries.get(id);
        return entry == null ? length : Math.min(length, entry.content.getShown());
    }

    /** 思考内容显示的字数；不在跟踪中返回 length */
    public int reasoningShown(long id, int length) {
        Entry entry = entries.get(id);
        return entry == null ? length : Math.min(length, entry.reasoning.getShown());
    }

    /** 正文最新几个字的淡入透明度（见 {@link TypingPacer#fadeAlphas(long)}） */
    @NonNull
    public float[] contentFade(long id, long nowNanos) {
        Entry entry = entries.get(id);
        return entry == null ? new float[0] : entry.content.fadeAlphas(nowNanos);
    }

    @NonNull
    public float[] reasoningFade(long id, long nowNanos) {
        Entry entry = entries.get(id);
        return entry == null ? new float[0] : entry.reasoning.fadeAlphas(nowNanos);
    }

    /** 已显示完全部已收到的正文（显示操作栏的条件之一） */
    public boolean isContentCaughtUp(long id) {
        Entry entry = entries.get(id);
        return entry == null || entry.content.isCaughtUp();
    }

    @Nullable
    Entry entry(long id) {
        return entries.get(id);
    }
}
