package com.heban.pestai.ai.compose;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.ai.data.AiRepository;
import com.heban.pestai.ai.util.AiConstants;
import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.network.ApiException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Q01 / Q02 输入栏的图片队列（AskAiViewModel、ChatViewModel 各持有一个，onCleared 时 {@link #dispose()}）。
 * <ul>
 *     <li>最多 {@link AiConstants#MAX_IMAGES} 张：一次选多了只保留前面的，并提示「最多只能添加 9 张图片」；</li>
 *     <li>加入后立即上传（{@link AiRepository#uploadImage(String)}：压缩 + POST files/image），
 *     同时最多 {@link AiConstants#MAX_CONCURRENT_UPLOADS} 张，其余排队，有一张结束就开始下一张；</li>
 *     <li>读取失败（ImageReadException）直接移出队列并提示「图片读取失败，请换一张试试」；
 *     其他错误标记为失败并提示「图片上传失败，点按重试」（登录过期、操作太频繁显示服务器原因），点按缩略图 {@link #retry(long)}；</li>
 *     <li>移除图片同时取消它的上传（dispose 即取消 OkHttp 请求）；发送时按加入顺序取 {@link #uploadedUrls()}。</li>
 * </ul>
 * 只在主线程调用；上传 subscribeOn(io) / observeOn(主线程)，单元测试中由 RxSchedulersRule 换成 trampoline 同步执行。
 */
public final class ImageQueue {

    private final AiRepository repository;
    /** 提示文案（ViewModel 的 showToast） */
    private final Consumer<String> toast;

    private final MutableLiveData<List<PendingImage>> items = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<Integer> count = new MutableLiveData<>(0);
    /** 正在上传的订阅（图片 ID → 订阅） */
    private final Map<Long, Disposable> tasks = new HashMap<>();
    private boolean disposed;

    public ImageQueue(@NonNull AiRepository repository, @NonNull Consumer<String> toast) {
        this.repository = repository;
        this.toast = toast;
    }

    // ==================== 对外数据 ====================

    /** 队列中的图片（按加入顺序，不可修改的列表） */
    @NonNull
    public LiveData<List<PendingImage>> getItems() {
        return items;
    }

    /** 图片张数（输入框提示文字、缩略图条是否显示） */
    @NonNull
    public LiveData<Integer> getCount() {
        return count;
    }

    public int size() {
        return current().size();
    }

    public boolean isEmpty() {
        return current().isEmpty();
    }

    /** 已满 9 张（输入栏相机与「+」变淡，点按提示） */
    public boolean isFull() {
        return size() >= AiConstants.MAX_IMAGES;
    }

    /** 还能添加几张 */
    public int remaining() {
        return Math.max(0, AiConstants.MAX_IMAGES - size());
    }

    /** 有图片在排队或上传中 */
    public boolean isUploading() {
        for (PendingImage image : current()) {
            if (image.isBusy()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasFailed() {
        for (PendingImage image : current()) {
            if (image.isFailed()) {
                return true;
            }
        }
        return false;
    }

    /** 已上传图片的地址，按加入顺序（与上传完成的先后无关） */
    @NonNull
    public List<String> uploadedUrls() {
        List<String> result = new ArrayList<>();
        for (PendingImage image : current()) {
            if (image.isDone() && image.getUrl() != null) {
                result.add(image.getUrl());
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** 已上传图片的本地 Uri，与 {@link #uploadedUrls()} 一一对应（发送后列表中显示本地图片，避免重新加载） */
    @NonNull
    public List<String> uploadedLocalUris() {
        List<String> result = new ArrayList<>();
        for (PendingImage image : current()) {
            if (image.isDone() && image.getUrl() != null) {
                result.add(image.getLocalUri());
            }
        }
        return Collections.unmodifiableList(result);
    }

    // ==================== 操作 ====================

    /**
     * 加入图片并开始上传。超过上限时只保留前面能放下的，并提示「最多只能添加 9 张图片」。
     *
     * @return 实际加入的张数
     */
    public int add(@Nullable List<String> uris) {
        if (disposed || uris == null || uris.isEmpty()) {
            return 0;
        }
        List<PendingImage> list = copy();
        int room = AiConstants.MAX_IMAGES - list.size();
        int added = 0;
        boolean truncated = false;
        for (String uri : uris) {
            if (uri == null || uri.isEmpty()) {
                continue;
            }
            if (added >= room) {
                truncated = true;
                break;
            }
            list.add(PendingImage.create(uri));
            added++;
        }
        if (added > 0) {
            publish(list);
            pump();
        }
        if (truncated) {
            toast.accept(AiConstants.TOAST_TOO_MANY_IMAGES);
        }
        return added;
    }

    /** 移除一张图片（✕），正在上传的同时取消 */
    public void remove(long id) {
        cancel(id);
        List<PendingImage> list = copy();
        int index = indexOf(list, id);
        if (index >= 0) {
            list.remove(index);
            publish(list);
            pump();
        }
    }

    /**
     * 上传失败的图片重新上传（点按缩略图）。
     *
     * @return 该图片是失败状态并已重新排队
     */
    public boolean retry(long id) {
        List<PendingImage> list = copy();
        int index = indexOf(list, id);
        if (disposed || index < 0 || !list.get(index).isFailed()) {
            return false;
        }
        list.set(index, list.get(index).withState(PendingImage.STATE_WAITING));
        publish(list);
        pump();
        return true;
    }

    /** 清空队列（发送后），取消所有上传 */
    public void clear() {
        cancelAll();
        if (!current().isEmpty()) {
            publish(new ArrayList<>());
        }
    }

    /** ViewModel onCleared：取消所有上传，之后不再接受新图片 */
    public void dispose() {
        disposed = true;
        cancelAll();
    }

    // ==================== 上传 ====================

    /** 同时上传数未满时按顺序开始排队中的图片（每次重新读取列表：上传可能同步完成并改变列表） */
    private void pump() {
        while (!disposed) {
            List<PendingImage> list = current();
            int running = 0;
            PendingImage next = null;
            for (PendingImage image : list) {
                if (image.getState() == PendingImage.STATE_UPLOADING) {
                    running++;
                } else if (next == null && image.getState() == PendingImage.STATE_WAITING) {
                    next = image;
                }
            }
            if (next == null || running >= AiConstants.MAX_CONCURRENT_UPLOADS) {
                return;
            }
            start(next);
        }
    }

    private void start(@NonNull PendingImage image) {
        final long id = image.getId();
        replace(id, image.withState(PendingImage.STATE_UPLOADING));
        Disposable task = repository.uploadImage(image.getLocalUri())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(url -> onUploaded(id, url), error -> onFailed(id, error));
        // 同步完成（单元测试中调度器为 trampoline）时订阅已结束，不再保存
        if (!task.isDisposed()) {
            tasks.put(id, task);
        }
    }

    private void onUploaded(long id, @NonNull String url) {
        tasks.remove(id);
        List<PendingImage> list = copy();
        int index = indexOf(list, id);
        if (index < 0) {
            return;
        }
        list.set(index, list.get(index).done(url));
        publish(list);
        pump();
    }

    private void onFailed(long id, @NonNull Throwable error) {
        tasks.remove(id);
        List<PendingImage> list = copy();
        int index = indexOf(list, id);
        if (index < 0) {
            return;
        }
        if (error instanceof ImageCompressor.ImageReadException) {
            // 读取失败：重试也不会成功，直接移出队列
            list.remove(index);
            publish(list);
            toast.accept(AiConstants.TOAST_IMAGE_READ_FAILED);
        } else {
            list.set(index, list.get(index).withState(PendingImage.STATE_FAILED));
            publish(list);
            int code = ApiException.codeOf(error);
            toast.accept(code == ApiException.UNAUTHORIZED || code == ApiException.TOO_FREQUENT
                    ? ApiException.messageOf(error) : AiConstants.TOAST_IMAGE_UPLOAD_FAILED);
        }
        pump();
    }

    private void cancel(long id) {
        Disposable task = tasks.remove(id);
        if (task != null) {
            task.dispose();
        }
    }

    private void cancelAll() {
        for (Disposable task : tasks.values()) {
            task.dispose();
        }
        tasks.clear();
    }

    // ==================== 列表工具 ====================

    @NonNull
    private List<PendingImage> current() {
        List<PendingImage> list = items.getValue();
        return list == null ? Collections.emptyList() : list;
    }

    @NonNull
    private List<PendingImage> copy() {
        return new ArrayList<>(current());
    }

    private void replace(long id, @NonNull PendingImage image) {
        List<PendingImage> list = copy();
        int index = indexOf(list, id);
        if (index >= 0) {
            list.set(index, image);
            publish(list);
        }
    }

    private void publish(@NonNull List<PendingImage> list) {
        items.setValue(Collections.unmodifiableList(list));
        int size = list.size();
        Integer oldCount = count.getValue();
        if (oldCount == null || oldCount != size) {
            count.setValue(size);
        }
    }

    private static int indexOf(@NonNull List<PendingImage> list, long id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId() == id) {
                return i;
            }
        }
        return -1;
    }
}
