package com.heban.pestai.ai.compose;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.ai.FakeAiRepository;
import com.heban.pestai.ai.RxSchedulersRule;
import com.heban.pestai.ai.util.AiConstants;
import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.rxjava3.subjects.SingleSubject;

public class ImageQueueTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxSchedulersRule rxRule = new RxSchedulersRule();

    private FakeAiRepository repo;
    private final List<String> toasts = new ArrayList<>();
    /** 每张图片的上传（测试控制完成顺序） */
    private final Map<String, SingleSubject<String>> uploads = new LinkedHashMap<>();
    private ImageQueue queue;

    @Before
    public void setUp() {
        repo = new FakeAiRepository();
        repo.upload = uri -> {
            SingleSubject<String> subject = SingleSubject.create();
            uploads.put(uri, subject);
            return subject;
        };
        queue = new ImageQueue(repo, toasts::add);
    }

    private static List<String> uris(int from, int count) {
        List<String> list = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            list.add("content://picker/" + i);
        }
        return list;
    }

    private List<PendingImage> items() {
        return queue.getItems().getValue();
    }

    private void finish(String uri) {
        uploads.get(uri).onSuccess(FakeAiRepository.urlOf(uri));
    }

    private List<Integer> states() {
        List<Integer> list = new ArrayList<>();
        for (PendingImage image : items()) {
            list.add(image.getState());
        }
        return list;
    }

    // ==================== 上限 ====================

    @Test
    public void add_capsAtNine_keepsFirstAndToasts() {
        assertEquals(7, queue.add(uris(0, 7)));
        assertTrue(toasts.isEmpty());
        assertEquals(2, queue.remaining());

        assertEquals("只保留前面能放下的 2 张", 2, queue.add(uris(7, 4)));
        assertEquals(Collections.singletonList(AiConstants.TOAST_TOO_MANY_IMAGES), toasts);
        assertEquals(9, queue.size());
        assertEquals("content://picker/8", items().get(8).getLocalUri());
        assertTrue(queue.isFull());
        assertEquals(Integer.valueOf(9), queue.getCount().getValue());
        assertEquals(0, queue.remaining());

        assertEquals("已满时一张也不加", 0, queue.add(uris(20, 1)));
        assertEquals(2, toasts.size());
        assertEquals(9, queue.size());
    }

    @Test
    public void add_exactlyRemaining_noToast() {
        queue.add(uris(0, 5));
        assertEquals(4, queue.add(uris(5, 4)));
        assertTrue(toasts.isEmpty());
        assertTrue(queue.isFull());
    }

    @Test
    public void add_ignoresEmptyUris() {
        assertEquals(1, queue.add(Arrays.asList("", null, "content://picker/1")));
        assertEquals(0, queue.add(null));
        assertEquals(1, queue.size());
    }

    // ==================== 并发上传 ====================

    @Test
    public void atMostThreeUploads_nextStartsWhenOneEnds() {
        queue.add(uris(0, 5));
        assertEquals("同时最多 3 张", uris(0, 3), repo.uploadCalls);
        assertEquals(Arrays.asList(PendingImage.STATE_UPLOADING, PendingImage.STATE_UPLOADING,
                PendingImage.STATE_UPLOADING, PendingImage.STATE_WAITING, PendingImage.STATE_WAITING), states());
        assertTrue("排队中也算上传中", items().get(4).isBusy());
        assertTrue(queue.isUploading());

        finish("content://picker/1");
        assertEquals(PendingImage.STATE_DONE, items().get(1).getState());
        assertEquals(FakeAiRepository.urlOf("content://picker/1"), items().get(1).getUrl());
        assertEquals("第 4 张开始上传", uris(0, 4), repo.uploadCalls);

        uploads.get("content://picker/0").onError(ApiException.of(ApiException.NETWORK));
        assertEquals(PendingImage.STATE_FAILED, items().get(0).getState());
        assertEquals("失败也让出名额", uris(0, 5), repo.uploadCalls);

        finish("content://picker/2");
        finish("content://picker/3");
        finish("content://picker/4");
        assertFalse(queue.isUploading());
        assertTrue(queue.hasFailed());
    }

    @Test
    public void synchronousUploads_allComplete() {
        repo.upload = uri -> io.reactivex.rxjava3.core.Single.just(FakeAiRepository.urlOf(uri));
        queue.add(uris(0, 9));
        assertEquals(9, repo.uploadCalls.size());
        assertFalse(queue.isUploading());
        assertEquals(9, queue.uploadedUrls().size());
    }

    // ==================== 移除 / 清空 ====================

    @Test
    public void remove_cancelsUpload_andStartsNext() {
        queue.add(uris(0, 4));
        long first = items().get(0).getId();
        assertTrue(uploads.get("content://picker/0").hasObservers());

        queue.remove(first);

        assertFalse("取消订阅即取消上传请求", uploads.get("content://picker/0").hasObservers());
        assertEquals(3, queue.size());
        assertEquals("content://picker/1", items().get(0).getLocalUri());
        assertEquals("让出名额后第 4 张开始上传", uris(0, 4), repo.uploadCalls);
        // 已取消的上传之后完成也不会改变队列
        uploads.get("content://picker/0").onSuccess("http://late.jpg");
        assertEquals(3, queue.size());
        assertTrue(queue.uploadedUrls().isEmpty());
    }

    @Test
    public void remove_doneImage_keepsOthers() {
        queue.add(uris(0, 2));
        finish("content://picker/0");
        finish("content://picker/1");
        queue.remove(items().get(0).getId());
        assertEquals(Collections.singletonList(FakeAiRepository.urlOf("content://picker/1")), queue.uploadedUrls());
        assertEquals(Integer.valueOf(1), queue.getCount().getValue());
    }

    @Test
    public void clear_disposesAllUploads() {
        queue.add(uris(0, 3));
        queue.clear();
        assertTrue(queue.isEmpty());
        for (SingleSubject<String> subject : uploads.values()) {
            assertFalse(subject.hasObservers());
        }
        assertEquals(Integer.valueOf(0), queue.getCount().getValue());
        assertFalse(queue.isFull());
    }

    @Test
    public void dispose_cancelsUploads_andRejectsNewImages() {
        queue.add(uris(0, 2));
        queue.dispose();
        assertFalse(uploads.get("content://picker/0").hasObservers());
        assertEquals(0, queue.add(uris(5, 1)));
    }

    // ==================== 失败 ====================

    @Test
    public void readFailure_removesImage_andToasts() {
        queue.add(uris(0, 2));
        uploads.get("content://picker/0").onError(new ImageCompressor.ImageReadException("bad image"));
        assertEquals(1, queue.size());
        assertEquals("content://picker/1", items().get(0).getLocalUri());
        assertEquals(Collections.singletonList(AiConstants.TOAST_IMAGE_READ_FAILED), toasts);
        assertFalse(queue.hasFailed());
    }

    @Test
    public void otherFailure_marksFailed_andToasts() {
        queue.add(uris(0, 1));
        uploads.get("content://picker/0").onError(ApiException.of(ApiException.SERVER_ERROR));
        PendingImage image = items().get(0);
        assertTrue(image.isFailed());
        assertNull(image.getUrl());
        assertTrue(queue.hasFailed());
        assertFalse(queue.isUploading());
        assertEquals(Collections.singletonList(AiConstants.TOAST_IMAGE_UPLOAD_FAILED), toasts);
    }

    @Test
    public void tooFrequent_showsServerMessage() {
        queue.add(uris(0, 1));
        uploads.get("content://picker/0").onError(ApiException.of(ApiException.TOO_FREQUENT));
        assertEquals("操作太频繁，请稍后再试", toasts.get(0));
    }

    @Test
    public void retry_reuploadsFailedImage() {
        queue.add(uris(0, 1));
        PendingImage image = items().get(0);
        assertFalse("上传中不能重试", queue.retry(image.getId()));

        uploads.get("content://picker/0").onError(ApiException.of(ApiException.NETWORK));
        assertTrue(queue.retry(image.getId()));
        assertEquals(2, repo.uploadCalls.size());
        assertEquals(PendingImage.STATE_UPLOADING, items().get(0).getState());
        assertEquals("同一张图片，ID 不变", image.getId(), items().get(0).getId());

        finish("content://picker/0");
        assertTrue(items().get(0).isDone());
        assertFalse(queue.hasFailed());
        assertFalse("完成后不能重试", queue.retry(image.getId()));
        assertFalse("不存在的图片", queue.retry(-1));
    }

    // ==================== 发送用的地址 ====================

    @Test
    public void uploadedUrls_followAddOrder_notCompletionOrder() {
        queue.add(uris(0, 3));
        finish("content://picker/2");
        finish("content://picker/0");
        finish("content://picker/1");
        assertEquals(Arrays.asList(FakeAiRepository.urlOf("content://picker/0"),
                FakeAiRepository.urlOf("content://picker/1"),
                FakeAiRepository.urlOf("content://picker/2")), queue.uploadedUrls());
        assertEquals(uris(0, 3), queue.uploadedLocalUris());
        assertEquals(uris(0, 3), PendingImage.localUris(items()));
    }
}
