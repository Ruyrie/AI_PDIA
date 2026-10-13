package com.heban.pestai.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

/** 逐字显示进度：开始、正文开始后思考内容直接显示完、结束后完成并移出跟踪、暂停后不跳字 */
public class TypingTrackerTest {

    private static final long FRAME = 33_333_333L;

    private final TypingTracker tracker = new TypingTracker();
    private long now = 5_000_000_000L;

    private void frames(int n) {
        for (int i = 0; i < n; i++) {
            now += FRAME;
            tracker.step(now);
        }
    }

    @Test
    public void untrackedMessages_showEverything() {
        assertEquals(42, tracker.contentShown(1, 42));
        assertEquals(7, tracker.reasoningShown(1, 7));
        assertEquals(0, tracker.contentFade(1, now).length);
        assertTrue(tracker.isContentCaughtUp(1));
        assertFalse(tracker.isActive());
    }

    @Test
    public void reasoningThenContent_reasoningSnapsWhenAnswerStarts() {
        tracker.begin(1);
        tracker.step(now); // 第一帧只记录时间
        tracker.setReasoning(1, 200);
        frames(3);
        int partial = tracker.reasoningShown(1, 200);
        assertTrue(partial > 0 && partial < 200);

        tracker.setContent(1, 10);
        assertEquals("正文开始后思考区折叠，思考内容直接显示完", 200, tracker.reasoningShown(1, 200));
        assertEquals(0, tracker.reasoningFade(1, now).length);
        assertEquals(0, tracker.contentShown(1, 10));
        frames(3);
        assertTrue(tracker.contentShown(1, 10) > 0);
        assertFalse(tracker.isContentCaughtUp(1));
    }

    @Test
    public void finish_completesAndDrains() {
        tracker.begin(7);
        tracker.step(now);
        tracker.setContent(7, 40);
        frames(2);
        assertTrue(tracker.isTracking(7));
        tracker.finish(7);
        // 0.3 秒显示完 + 0.16 秒淡入
        frames(15);
        assertFalse(tracker.isTracking(7));
        assertFalse(tracker.isActive());
        assertEquals(40, tracker.contentShown(7, 40));
        assertEquals(Collections.singletonList(7L), tracker.drainCompleted());
        assertTrue("只返回一次", tracker.drainCompleted().isEmpty());
    }

    @Test
    public void step_reportsDirtyOnlyWhenSomethingChanges() {
        tracker.begin(3);
        assertFalse("第一帧 dt 为 0", tracker.step(now));
        now += FRAME;
        assertFalse("没有内容", tracker.step(now));
        tracker.setContent(3, 5);
        now += FRAME;
        assertTrue(tracker.step(now));
    }

    @Test
    public void pause_nextFrameDoesNotJump() {
        tracker.begin(2);
        tracker.step(now);
        tracker.setContent(2, 100);
        frames(3);
        int before = tracker.contentShown(2, 100);
        tracker.pause();
        // 页面在后台停了 10 秒
        now += 10_000_000_000L;
        tracker.step(now);
        assertEquals(before, tracker.contentShown(2, 100));
        frames(1);
        assertTrue(tracker.contentShown(2, 100) > before);
    }

    @Test
    public void remove_andClear() {
        tracker.begin(1);
        tracker.begin(2);
        tracker.remove(1);
        assertFalse(tracker.isTracking(1));
        assertTrue(tracker.isTracking(2));
        tracker.clear();
        assertFalse(tracker.isActive());
    }
}
