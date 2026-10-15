package com.heban.pestai.ai.compose;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 输入栏图片队列中的一张图片（不可变，状态变化时返回新对象，便于 DiffUtil 比较）。
 * 选好后立即开始上传：WAITING（排队，界面与上传中相同）→ UPLOADING → DONE（得到 url）/ FAILED（点按重试）；
 * 读取失败的图片直接移出队列，没有对应状态。
 */
public final class PendingImage {

    /** 排队等待上传（同时上传数已满） */
    public static final int STATE_WAITING = 0;
    public static final int STATE_UPLOADING = 1;
    public static final int STATE_FAILED = 2;
    public static final int STATE_DONE = 3;

    private static final AtomicLong IDS = new AtomicLong();

    private final long id;
    @NonNull
    private final String localUri;
    /** 上传成功后的完整地址，其余状态为 null */
    @Nullable
    private final String url;
    private final int state;

    private PendingImage(long id, @NonNull String localUri, @Nullable String url, int state) {
        this.id = id;
        this.localUri = localUri;
        this.url = url;
        this.state = state;
    }

    /** 新加入队列的图片：排队等待上传 */
    @NonNull
    static PendingImage create(@NonNull String localUri) {
        return new PendingImage(IDS.incrementAndGet(), localUri, null, STATE_WAITING);
    }

    @NonNull
    PendingImage withState(int newState) {
        return new PendingImage(id, localUri, newState == STATE_DONE ? url : null, newState);
    }

    @NonNull
    PendingImage done(@NonNull String newUrl) {
        return new PendingImage(id, localUri, newUrl, STATE_DONE);
    }

    public long getId() {
        return id;
    }

    @NonNull
    public String getLocalUri() {
        return localUri;
    }

    @Nullable
    public String getUrl() {
        return url;
    }

    public int getState() {
        return state;
    }

    /** 排队或上传中：缩略图显示遮罩 + 进度 */
    public boolean isBusy() {
        return state == STATE_WAITING || state == STATE_UPLOADING;
    }

    public boolean isFailed() {
        return state == STATE_FAILED;
    }

    public boolean isDone() {
        return state == STATE_DONE;
    }

    /** 列表中全部图片的本地 Uri（按顺序），用于全屏预览 */
    @NonNull
    public static List<String> localUris(@Nullable List<PendingImage> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(list.size());
        for (PendingImage image : list) {
            result.add(image.localUri);
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PendingImage that = (PendingImage) o;
        return id == that.id
                && state == that.state
                && localUri.equals(that.localUri)
                && Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, localUri, url, state);
    }

    @NonNull
    @Override
    public String toString() {
        return "PendingImage{" + id + ", state=" + state + ", localUri='" + localUri + "', url='" + url + "'}";
    }
}
