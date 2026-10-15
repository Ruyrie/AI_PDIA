package com.heban.pestai.base.widget;

import static com.heban.pestai.base.widget.AvatarLoadState.NO_SLOT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.base.widget.AvatarLoadState.Action;

import org.junit.Before;
import org.junit.Test;

public class AvatarLoadStateTest {

    private static final String LOCAL = "content://media/picker/1";
    private static final String URL = "http://10.0.2.2:8080/files/avatar/20261014/a.jpg";
    private static final String OTHER = "http://10.0.2.2:8080/files/avatar/20261014/b.jpg";

    private AvatarLoadState state;

    @Before
    public void setUp() {
        state = new AvatarLoadState();
    }

    @Test
    public void emptyImage_showsInitialWithoutLoading() {
        assertEquals(Action.NONE, state.request(null));      // 初始即为首字
        assertEquals(Action.NONE, state.request(""));
        assertEquals(Action.NONE, state.request("  "));
        assertEquals(NO_SLOT, state.pendingSlot());
        assertEquals(NO_SLOT, state.frontSlot());
    }

    @Test
    public void firstLoad_becomesFrontWhenReady() {
        assertEquals(Action.LOAD, state.request(URL));
        assertEquals(0, state.pendingSlot());
        assertEquals(NO_SLOT, state.frontSlot());           // 加载完成前仍显示首字

        assertTrue(state.onLoaded(0));
        assertEquals(0, state.frontSlot());
        assertEquals(URL, state.frontModel());
        assertEquals(NO_SLOT, state.pendingSlot());
    }

    @Test
    public void sameImage_whileLoadingOrShown_isIgnored() {
        state.request(URL);
        assertEquals(Action.NONE, state.request(URL));      // 加载中重新绑定：不重启加载
        state.onLoaded(0);
        assertEquals(Action.NONE, state.request(URL));      // 已显示：不重新加载
    }

    @Test
    public void switchImage_loadsIntoOtherSlotAndKeepsOldUntilReady() {
        // A06：本地预览 → 上传成功后的网络地址
        state.request(LOCAL);
        state.onLoaded(0);

        assertEquals(Action.LOAD, state.request(URL));
        assertEquals(1, state.pendingSlot());
        assertEquals(0, state.frontSlot());                 // 新图就绪前旧图一直显示，不闪首字
        assertEquals(LOCAL, state.frontModel());

        assertTrue(state.onLoaded(1));
        assertEquals(1, state.frontSlot());
        assertEquals(URL, state.frontModel());

        // 再换一次：轮回到槽 0
        assertEquals(Action.LOAD, state.request(OTHER));
        assertEquals(0, state.pendingSlot());
    }

    @Test
    public void failure_showsInitialAndAllowsRetryOfSameImage() {
        state.request(URL);
        assertTrue(state.onFailed(0));
        assertEquals(NO_SLOT, state.frontSlot());
        assertNull(state.requested());

        // 之后再绑定同一地址（如网络恢复后资料刷新）会重新加载，而不是被「相同请求」挡住
        assertEquals(Action.LOAD, state.request(URL));
        assertEquals(0, state.pendingSlot());
    }

    @Test
    public void failure_thenClear_isNoOp() {
        state.request(URL);
        state.onFailed(0);
        // 失败后已显示首字，清空头像不需要再处理
        assertEquals(Action.NONE, state.request(null));
    }

    @Test
    public void failureAfterSwitch_dropsOldImageAndShowsInitial() {
        state.request(LOCAL);
        state.onLoaded(0);
        state.request(URL);

        assertTrue(state.onFailed(1));
        assertEquals(NO_SLOT, state.frontSlot());           // 回退为首字（调用方释放旧的前台槽）
        assertNull(state.frontModel());

        // 旧图已释放：再请求它需要重新加载
        assertEquals(Action.LOAD, state.request(LOCAL));
    }

    @Test
    public void backToFrontImage_cancelsPendingLoad() {
        state.request(URL);
        state.onLoaded(0);
        state.request(OTHER);
        assertEquals(1, state.pendingSlot());

        assertEquals(Action.KEEP_FRONT, state.request(URL));
        assertEquals(NO_SLOT, state.pendingSlot());
        assertEquals(0, state.frontSlot());

        // 已取消的加载晚到的结果被忽略
        assertFalse(state.onLoaded(1));
        assertFalse(state.onFailed(1));
        assertEquals(0, state.frontSlot());
        assertEquals(URL, state.frontModel());
    }

    @Test
    public void clearImage_dropsFrontAndPending() {
        state.request(URL);
        state.onLoaded(0);
        state.request(OTHER);

        assertEquals(Action.SHOW_INITIAL, state.request(""));
        assertEquals(NO_SLOT, state.frontSlot());
        assertEquals(NO_SLOT, state.pendingSlot());
        assertFalse(state.onLoaded(1));
        assertEquals(Action.NONE, state.request(null));
    }

    @Test
    public void staleSlotResults_areIgnored() {
        state.request(URL);
        assertFalse(state.onLoaded(1));
        assertFalse(state.onFailed(1));
        assertFalse(state.onLoaded(NO_SLOT));
        assertEquals(0, state.pendingSlot());
    }
}
