package com.heban.pestai.ai.compose;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.ai.FakeSharedPreferences;
import com.heban.pestai.ai.util.AiConstants;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 输入方式状态：切换并记住、权限时机、识别后临时文字、发送后恢复、Q01 回到页面同步 */
public class InputModeTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();

    private InputModeStore store;
    private List<String> toasts;

    @Before
    public void setUp() {
        store = new InputModeStore(new FakeSharedPreferences());
        toasts = new ArrayList<>();
    }

    private InputMode create() {
        return new InputMode(store, toasts::add);
    }

    @Test
    public void initial_isRemembered() {
        assertFalse("没有记录时为文字", create().isVoice());
        store.setVoiceMode(true);
        InputMode mode = create();
        assertTrue("打开页面时为记住的方式", mode.isVoice());
        assertEquals(Boolean.TRUE, mode.getVoice().getValue());
    }

    @Test
    public void toggle_withPermission_switchesAndRemembers() {
        InputMode mode = create();
        List<Boolean> values = new ArrayList<>();
        mode.getVoice().observeForever(values::add);

        assertFalse("有权限不需要申请", mode.toggle(true));
        assertTrue(mode.isVoice());
        assertTrue("记住", store.isVoiceMode());

        assertFalse("语音 → 文字不需要权限", mode.toggle(false));
        assertFalse(mode.isVoice());
        assertFalse(store.isVoiceMode());
        assertEquals(Arrays.asList(false, true, false), values);
        assertTrue(toasts.isEmpty());
    }

    @Test
    public void toggle_withoutPermission_waitsForGrant() {
        InputMode mode = create();
        assertTrue("没有权限：页面申请", mode.toggle(false));
        assertFalse("授权前不切换", mode.isVoice());
        assertFalse(store.isVoiceMode());

        mode.onPermissionResult(true);
        assertTrue("授权后切到语音", mode.isVoice());
        assertTrue(store.isVoiceMode());
        assertTrue("切换本身就是反馈，不再提示", toasts.isEmpty());
    }

    @Test
    public void toggle_permissionDenied_staysText_toasts() {
        InputMode mode = create();
        assertTrue(mode.toggle(false));
        mode.onPermissionResult(false);
        assertFalse(mode.isVoice());
        assertFalse(store.isVoiceMode());
        assertEquals(Collections.singletonList(AiConstants.TOAST_MIC_DENIED), toasts);

        // 再次点按仍然申请
        assertTrue(mode.toggle(false));
    }

    @Test
    public void permissionResult_fromHoldToTalk_onlyToasts() {
        store.setVoiceMode(true);
        InputMode mode = create();
        // 语音输入时权限被撤销：按住「按住 说话」时申请
        mode.onPermissionResult(true);
        mode.onPermissionResult(false);
        assertTrue("仍是语音", mode.isVoice());
        assertEquals(Arrays.asList(AiConstants.TOAST_MIC_GRANTED, AiConstants.TOAST_MIC_DENIED), toasts);
    }

    @Test
    public void permissionResult_onlyFirstResultAfterToggleSwitches() {
        InputMode mode = create();
        assertTrue(mode.toggle(false));
        mode.onPermissionResult(false);
        // 拒绝后等待已清除：之后收到的授权结果（不是这次点按申请的）只提示，不自动切换
        mode.onPermissionResult(true);
        assertFalse(mode.isVoice());
        assertEquals(Arrays.asList(AiConstants.TOAST_MIC_DENIED, AiConstants.TOAST_MIC_GRANTED), toasts);
    }

    @Test
    public void recognized_temporaryText_notRemembered_restoredAfterSend() {
        store.setVoiceMode(true);
        InputMode mode = create();
        mode.showTextTemporarily();
        assertFalse("识别后临时切到文字", mode.isVoice());
        assertTrue(mode.isTemporaryText());
        assertTrue("不改变记住的方式", store.isVoiceMode());

        mode.syncRemembered();
        assertFalse("回到页面时临时文字保持不变", mode.isVoice());

        mode.restore();
        assertTrue("发送后回到记住的语音", mode.isVoice());
        assertFalse(mode.isTemporaryText());
    }

    @Test
    public void recognized_inTextMode_noChange() {
        InputMode mode = create();
        mode.showTextTemporarily();
        assertFalse(mode.isVoice());
        assertFalse("本来就是文字，不算临时", mode.isTemporaryText());
        mode.restore();
        assertFalse(mode.isVoice());
    }

    @Test
    public void toggleAfterTemporaryText_isExplicitChoice() {
        store.setVoiceMode(true);
        InputMode mode = create();
        mode.showTextTemporarily();
        // 临时文字时点麦克风：切回语音
        assertFalse(mode.toggle(true));
        assertTrue(mode.isVoice());
        assertFalse(mode.isTemporaryText());
        // 再点键盘：主动选择文字并记住，发送后保持文字
        mode.toggle(true);
        assertFalse(store.isVoiceMode());
        mode.restore();
        assertFalse(mode.isVoice());
    }

    @Test
    public void syncRemembered_followsOtherPage() {
        InputMode q01 = create();
        InputMode q02 = create();
        q02.toggle(true);
        assertFalse("Q01 不会立即变化", q01.isVoice());
        q01.syncRemembered();
        assertTrue("Q01 回到页面时同步", q01.isVoice());
    }
}
