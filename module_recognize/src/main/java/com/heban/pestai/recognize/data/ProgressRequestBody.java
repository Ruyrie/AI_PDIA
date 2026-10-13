package com.heban.pestai.recognize.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;

/**
 * 包装图片文件的 RequestBody，OkHttp 写出请求体时统计已写出的字节数并回调上传百分比。
 *
 * <p>调试版 HttpLoggingInterceptor（BODY 级别）会先把请求体写进一个 {@link Buffer} 打印日志，
 * 如果也统计会让进度跑两遍，因此 sink 是 Buffer 时直接写出、不统计。</p>
 *
 * <p>{@link Listener#onProgress(int)} 在 OkHttp 的写线程回调，只在百分比变化时回调。</p>
 */
public class ProgressRequestBody extends RequestBody {

    public interface Listener {
        /** @param percent 0–100 */
        void onProgress(int percent);
    }

    private final RequestBody delegate;
    private final Listener listener;

    public ProgressRequestBody(@NonNull RequestBody delegate, @NonNull Listener listener) {
        this.delegate = delegate;
        this.listener = listener;
    }

    @Nullable
    @Override
    public MediaType contentType() {
        return delegate.contentType();
    }

    @Override
    public long contentLength() throws IOException {
        return delegate.contentLength();
    }

    @Override
    public void writeTo(@NonNull BufferedSink sink) throws IOException {
        if (sink instanceof Buffer) {
            delegate.writeTo(sink);
            return;
        }
        CountingSink counting = new CountingSink(sink, contentLength(), listener);
        BufferedSink buffered = Okio.buffer(counting);
        delegate.writeTo(buffered);
        buffered.flush();
    }

    /** 已写出字节数 → 百分比 0–100；总长度未知（≤ 0）时为 0 */
    public static int percentOf(long written, long total) {
        if (total <= 0 || written <= 0) {
            return 0;
        }
        if (written >= total) {
            return 100;
        }
        return (int) (written * 100 / total);
    }

    private static final class CountingSink extends ForwardingSink {
        private final long total;
        private final Listener listener;
        private long written;
        private int lastPercent = -1;

        CountingSink(@NonNull okio.Sink delegate, long total, @NonNull Listener listener) {
            super(delegate);
            this.total = total;
            this.listener = listener;
        }

        @Override
        public void write(@NonNull Buffer source, long byteCount) throws IOException {
            super.write(source, byteCount);
            written += byteCount;
            int percent = percentOf(written, total);
            if (percent != lastPercent) {
                lastPercent = percent;
                listener.onProgress(percent);
            }
        }
    }
}
