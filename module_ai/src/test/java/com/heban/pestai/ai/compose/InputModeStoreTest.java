package com.heban.pestai.ai.compose;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.ai.FakeSharedPreferences;

import org.junit.Test;

/** 记住的输入方式（Q01 / Q02 共用的 SharedPreferences） */
public class InputModeStoreTest {

    @Test
    public void fileAndKey() {
        assertEquals("ai_composer", InputModeStore.PREFS_NAME);
        assertEquals("voice_mode", InputModeStore.KEY_VOICE_MODE);
    }

    @Test
    public void default_isTextMode() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        InputModeStore store = new InputModeStore(prefs);
        assertFalse("没有记录时为文字输入", store.isVoiceMode());
        assertEquals("读取不写入", 0, prefs.writes);
    }

    @Test
    public void setVoiceMode_persistsAndSharedAcrossInstances() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        InputModeStore q01 = new InputModeStore(prefs);
        q01.setVoiceMode(true);
        assertTrue(q01.isVoiceMode());
        assertEquals(Boolean.TRUE, prefs.getAll().get(InputModeStore.KEY_VOICE_MODE));
        assertTrue("同一个文件：Q02 读到 Q01 的切换", new InputModeStore(prefs).isVoiceMode());

        q01.setVoiceMode(false);
        assertFalse(new InputModeStore(prefs).isVoiceMode());
        assertEquals(Boolean.FALSE, prefs.getAll().get(InputModeStore.KEY_VOICE_MODE));
    }

    @Test
    public void setSameValue_doesNotWriteAgain() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        InputModeStore store = new InputModeStore(prefs);
        store.setVoiceMode(false);
        assertEquals("第一次明确选择文字也要记下", 1, prefs.writes);
        store.setVoiceMode(false);
        assertEquals(1, prefs.writes);
        store.setVoiceMode(true);
        store.setVoiceMode(true);
        assertEquals(2, prefs.writes);
    }
}
