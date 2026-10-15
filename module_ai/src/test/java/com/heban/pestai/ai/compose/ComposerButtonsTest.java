package com.heban.pestai.ai.compose;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.ai.util.AiConstants;

import org.junit.Test;

/** 输入栏按钮显示规则（Q01 / Q02 共用，文字 / 语音两种输入方式） */
public class ComposerButtonsTest {

    @Test
    public void blankText_noImages_cameraMicAndAlbumOnly() {
        for (String text : new String[]{null, "", "   ", "\n\t"}) {
            ComposerButtons b = ComposerButtons.of(text, 0, false, true);
            assertTrue(b.isCameraVisible());
            assertTrue("没有文字时显示麦克风", b.isMicVisible());
            assertTrue(b.isAlbumVisible());
            assertFalse("没有输入时不显示发送按钮", b.isActionVisible());
            assertFalse(b.isActionEnabled());
            assertFalse(b.canSend());
            assertFalse(b.isStop());
            assertFalse(b.isAddDimmed());
            assertEquals(ComposerButtons.EMPTY, b);
        }
    }

    @Test
    public void text_onlySendButton() {
        ComposerButtons b = ComposerButtons.of("番茄叶片发黄", 0, false, true);
        assertFalse(b.isCameraVisible());
        assertFalse("输入文字后麦克风隐藏", b.isMicVisible());
        assertFalse(b.isAlbumVisible());
        assertTrue(b.isActionVisible());
        assertTrue(b.isActionEnabled());
        assertTrue(b.canSend());
        assertFalse(b.isStop());

        ComposerButtons withImages = ComposerButtons.of(" 看看这张 ", 3, false, true);
        assertFalse("有文字时即使有图片也只显示发送", withImages.isCameraVisible());
        assertFalse(withImages.isAlbumVisible());
        assertTrue(withImages.isActionVisible());
    }

    @Test
    public void blankText_withImages_allThree() {
        ComposerButtons b = ComposerButtons.of("", 1, false, true);
        assertTrue(b.isCameraVisible());
        assertTrue(b.isMicVisible());
        assertTrue(b.isAlbumVisible());
        assertTrue(b.isActionVisible());
        assertTrue("只发图片也能发送", b.canSend());
        assertFalse(b.isAddDimmed());
    }

    @Test
    public void nineImages_cameraAndAlbumDimmed() {
        ComposerButtons full = ComposerButtons.of("", AiConstants.MAX_IMAGES, false, true);
        assertTrue(full.isAddDimmed());
        assertTrue("变淡但仍显示（点按提示）", full.isCameraVisible());
        assertTrue(full.isAlbumVisible());
        assertFalse(ComposerButtons.of("", AiConstants.MAX_IMAGES - 1, false, true).isAddDimmed());
        assertTrue("有文字时相机隐藏，但仍记录已满", ComposerButtons.of("a", AiConstants.MAX_IMAGES, false, true)
                .isAddDimmed());
    }

    @Test
    public void streaming_stopAlwaysShown_cameraAndAlbumFollowText() {
        ComposerButtons blank = ComposerButtons.of("", 0, true, true);
        assertTrue("生成中没有文字也显示停止按钮", blank.isActionVisible());
        assertTrue(blank.isStop());
        assertTrue("停止按钮可点", blank.isActionEnabled());
        assertFalse("停止按钮不是发送", blank.canSend());
        assertTrue(blank.isCameraVisible());
        assertTrue(blank.isAlbumVisible());

        ComposerButtons typed = ComposerButtons.of("再问一个", 0, true, true);
        assertTrue(typed.isStop());
        assertTrue(typed.isActionVisible());
        assertFalse(typed.isCameraVisible());
        assertFalse(typed.isAlbumVisible());

        ComposerButtons loading = ComposerButtons.of("", 0, true, false);
        assertTrue(loading.isActionEnabled());
    }

    @Test
    public void notReady_sendShownButDisabled() {
        ComposerButtons b = ComposerButtons.of("这个病怎么处理？", 0, false, false);
        assertTrue("Q02 打开历史对话加载中：按钮显示但不可点", b.isActionVisible());
        assertFalse(b.isActionEnabled());
        assertFalse(b.canSend());
        assertFalse(ComposerButtons.of("", 2, false, false).isActionEnabled());
    }

    @Test
    public void mic_followsTextOnly_likeCameraAndAlbum() {
        assertTrue("初始状态显示麦克风", ComposerButtons.EMPTY.isMicVisible());
        assertTrue("只有空白也显示", ComposerButtons.of(" \n ", 0, false, true).isMicVisible());
        assertFalse(ComposerButtons.of("a", 0, false, true).isMicVisible());
        assertFalse(ComposerButtons.of("看看这张", 2, false, true).isMicVisible());
        assertTrue("有图片没文字仍显示", ComposerButtons.of("", 3, false, true).isMicVisible());
        assertTrue("已满 9 张仍显示（不影响语音输入）", ComposerButtons.of("", AiConstants.MAX_IMAGES, false, true)
                .isMicVisible());
        assertTrue("Q02 生成中可以语音输入", ComposerButtons.of("", 0, true, true).isMicVisible());
        assertFalse("生成中输入了文字同样隐藏", ComposerButtons.of("再问", 0, true, true).isMicVisible());
        assertTrue("历史对话加载中也显示（只填文字）", ComposerButtons.of("", 0, false, false).isMicVisible());
        for (int images = 0; images <= AiConstants.MAX_IMAGES; images++) {
            for (boolean streaming : new boolean[]{false, true}) {
                ComposerButtons blank = ComposerButtons.of("", images, streaming, true);
                ComposerButtons typed = ComposerButtons.of("x", images, streaming, true);
                assertEquals("麦克风与相机、「+」规则相同", blank.isCameraVisible(), blank.isMicVisible());
                assertEquals(blank.isAlbumVisible(), blank.isMicVisible());
                assertEquals(typed.isCameraVisible(), typed.isMicVisible());
            }
        }
    }

    // ==================== 语音输入 ====================

    @Test
    public void textMode_inputAndMic_noTalkOrKeyboard() {
        for (String text : new String[]{"", "番茄"}) {
            for (int images = 0; images <= AiConstants.MAX_IMAGES; images++) {
                ComposerButtons b = ComposerButtons.of(text, images, false, true);
                assertEquals("4 参数即文字输入", b, ComposerButtons.of(text, images, false, true, false));
                assertFalse(b.isVoiceMode());
                assertTrue("文字输入显示输入框", b.isInputVisible());
                assertFalse(b.isTalkVisible());
                assertFalse("文字输入不显示键盘图标", b.isKeyboardVisible());
            }
        }
        assertFalse(ComposerButtons.EMPTY.isVoiceMode());
    }

    @Test
    public void voiceMode_onlyTalkAndKeyboard() {
        ComposerButtons b = ComposerButtons.of("", 0, false, true, true);
        assertTrue(b.isVoiceMode());
        assertTrue("「按住 说话」代替相机与输入框", b.isTalkVisible());
        assertFalse(b.isInputVisible());
        assertFalse("语音输入不显示相机", b.isCameraVisible());
        assertTrue("键盘图标在麦克风的位置", b.isKeyboardVisible());
        assertFalse("麦克风换成键盘", b.isMicVisible());
        assertFalse("语音输入不显示「+」", b.isAlbumVisible());
        assertFalse("不显示发送", b.isActionVisible());
        assertFalse(b.canSend());
    }

    @Test
    public void voiceMode_hiddenDraftOrImages_stillOnlyTalkAndKeyboard() {
        for (String draft : new String[]{"", "刚才打的字"}) {
            for (int images = 0; images <= AiConstants.MAX_IMAGES; images++) {
                ComposerButtons b = ComposerButtons.of(draft, images, false, true, true);
                assertFalse("草稿与图片保留，但语音输入时不显示发送", b.isActionVisible());
                assertFalse(b.isCameraVisible());
                assertFalse(b.isAlbumVisible());
                assertFalse(b.isMicVisible());
                assertTrue(b.isKeyboardVisible());
                assertTrue(b.isTalkVisible());
            }
        }
    }

    @Test
    public void voiceMode_streaming_showsStopOnly() {
        ComposerButtons streaming = ComposerButtons.of("", 0, true, true, true);
        assertTrue("Q02 生成中显示停止，否则无法停止生成", streaming.isActionVisible());
        assertTrue(streaming.isStop());
        assertTrue(streaming.isActionEnabled());
        assertFalse(streaming.canSend());
        assertTrue("生成中也可以语音输入", streaming.isTalkVisible());
        assertTrue(streaming.isKeyboardVisible());
        assertFalse(streaming.isCameraVisible());
        assertFalse(streaming.isAlbumVisible());
    }

    @Test
    public void micAndKeyboard_neverBothVisible_sameSlot() {
        for (boolean voice : new boolean[]{false, true}) {
            for (String text : new String[]{"", "x"}) {
                for (int images = 0; images <= AiConstants.MAX_IMAGES; images++) {
                    for (boolean streaming : new boolean[]{false, true}) {
                        ComposerButtons b = ComposerButtons.of(text, images, streaming, true, voice);
                        assertFalse(b.isMicVisible() && b.isKeyboardVisible());
                        assertEquals(b.isInputVisible(), !b.isTalkVisible());
                        assertEquals("键盘只在语音输入时显示", voice, b.isKeyboardVisible());
                        if (voice) {
                            assertEquals("语音输入只在生成中显示停止", streaming, b.isActionVisible());
                        } else {
                            assertEquals(ComposerButtons.of(text, images, streaming, true).isActionVisible(),
                                    b.isActionVisible());
                        }
                    }
                }
            }
        }
    }

    @Test
    public void modeSwitch_isVisibilityChange() {
        ComposerButtons text = ComposerButtons.EMPTY;
        ComposerButtons voice = ComposerButtons.of("", 0, false, true, true);
        assertTrue("输入框 ↔「按住 说话」、麦克风 ↔ 键盘需要过渡", text.visibilityDiffers(voice));
        assertTrue(voice.visibilityDiffers(text));
        assertNotEquals(text, voice);
        assertNotEquals(text.hashCode(), voice.hashCode());
        assertTrue("有草稿的文字输入 → 语音：相机、键盘、「+」出现",
                ComposerButtons.of("a", 0, false, true).visibilityDiffers(ComposerButtons.of("a", 0, false, true, true)));
        assertEquals("语音输入时草稿内容不影响按钮", ComposerButtons.of("a", 0, false, true, true),
                ComposerButtons.of("bbb", 0, false, true, true));
    }

    @Test
    public void visibilityDiffers_onlyWhenAButtonAppearsOrDisappears() {
        ComposerButtons empty = ComposerButtons.EMPTY;
        assertTrue(empty.visibilityDiffers(ComposerButtons.of("a", 0, false, true)));
        assertTrue(empty.visibilityDiffers(ComposerButtons.of("", 1, false, true)));
        ComposerButtons send = ComposerButtons.of("a", 0, false, true);
        assertFalse("发送 → 停止只换图标", send.visibilityDiffers(ComposerButtons.of("a", 0, true, true)));
        assertFalse("可点状态变化不算", send.visibilityDiffers(ComposerButtons.of("a", 0, false, false)));
        assertFalse("变淡不算", ComposerButtons.of("", 8, false, true)
                .visibilityDiffers(ComposerButtons.of("", 9, false, true)));
    }

    @Test
    public void equalsAndHashCode() {
        assertEquals(ComposerButtons.of("a", 0, false, true), ComposerButtons.of("b", 0, false, true));
        assertEquals(ComposerButtons.of("a", 0, false, true).hashCode(), ComposerButtons.of("b", 0, false, true).hashCode());
        assertEquals("张数只影响是否有图片与是否已满", ComposerButtons.of("", 2, false, true),
                ComposerButtons.of("", 5, false, true));
        assertNotEquals(ComposerButtons.of("a", 0, false, true), ComposerButtons.of("a", 0, true, true));
        assertNotEquals(ComposerButtons.of("", 8, false, true), ComposerButtons.of("", 9, false, true));
    }
}
