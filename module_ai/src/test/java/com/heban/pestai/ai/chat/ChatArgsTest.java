package com.heban.pestai.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.common.router.RouterPath;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** Q01 → Q02 的图片参数与列表图片项的显示源 */
public class ChatArgsTest {

    private static final List<String> URIS = Arrays.asList("content://picker/1", "content://picker/2");
    private static final List<String> URLS = Arrays.asList("http://x/1.jpg", "http://x/2.jpg");

    @Test
    public void ofQuestion_carriesUploadedImages() {
        ChatArgs args = ChatArgs.ofQuestion("看看这两张", URIS, URLS);
        assertEquals(RouterPath.NO_RECORD, args.recordId);
        assertEquals("看看这两张", args.question);
        assertEquals(URIS, args.imageUris);
        assertEquals(URLS, args.imageUrls);
        assertTrue(args.hasImages());
        assertFalse(args.hasSession());
        assertEquals(args, ChatArgs.ofQuestion("看看这两张", URIS, URLS));
    }

    @Test
    public void textOnly_andSession_haveNoImages() {
        assertFalse(ChatArgs.ofQuestion("蚜虫怎么防？").hasImages());
        assertTrue(ChatArgs.ofRecord(2036).imageUrls.isEmpty());
        ChatArgs session = ChatArgs.ofSession(700);
        assertTrue(session.imageUris.isEmpty());
        assertNull(session.question);
    }

    @Test
    public void mismatchedLists_keepUrlsOnly() {
        ChatArgs args = new ChatArgs(RouterPath.NO_RECORD, null, Collections.singletonList("content://picker/1"),
                URLS, ChatArgs.NO_SESSION);
        assertEquals(URLS, args.imageUrls);
        assertTrue("本地 Uri 与地址数量不一致时不用本地图片", args.imageUris.isEmpty());

        ChatArgs nulls = new ChatArgs(RouterPath.NO_RECORD, "问题", null, null, ChatArgs.NO_SESSION);
        assertFalse(nulls.hasImages());
    }

    @Test
    public void imageMessage_prefersLocalSources() {
        ChatMessage sent = ChatMessage.userImages(URIS, URLS, new Date());
        assertEquals(URIS, sent.getImageSources());
        assertEquals(URLS, sent.getImageUrls());

        ChatMessage history = ChatMessage.userImages(Collections.emptyList(), URLS, new Date());
        assertEquals(URLS, history.getImageSources());

        ChatMessage mismatched = ChatMessage.userImages(Collections.singletonList("content://picker/1"), URLS,
                new Date());
        assertTrue(mismatched.getLocalUris().isEmpty());
        assertEquals(URLS, mismatched.getImageSources());
    }
}
