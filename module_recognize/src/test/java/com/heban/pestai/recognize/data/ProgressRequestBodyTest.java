package com.heban.pestai.recognize.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.Okio;

public class ProgressRequestBodyTest {

    private static final MediaType JPEG = MediaType.get("image/jpeg");

    @Test
    public void percentOf() {
        assertEquals(0, ProgressRequestBody.percentOf(0, 100));
        assertEquals(50, ProgressRequestBody.percentOf(50, 100));
        assertEquals(99, ProgressRequestBody.percentOf(999, 1000));
        assertEquals(100, ProgressRequestBody.percentOf(1000, 1000));
        assertEquals(100, ProgressRequestBody.percentOf(1200, 1000));
        assertEquals(0, ProgressRequestBody.percentOf(10, 0));
        assertEquals(0, ProgressRequestBody.percentOf(10, -1));
    }

    @Test
    public void writeTo_reportsMonotonicProgressUpTo100() throws Exception {
        byte[] data = new byte[200_000];
        List<Integer> progress = new ArrayList<>();
        ProgressRequestBody body = new ProgressRequestBody(RequestBody.create(data, JPEG), progress::add);
        assertEquals(data.length, body.contentLength());
        assertEquals(JPEG, body.contentType());

        BufferedSink sink = Okio.buffer(Okio.blackhole());
        body.writeTo(sink);
        sink.flush();

        assertFalse(progress.isEmpty());
        assertEquals(100, (int) progress.get(progress.size() - 1));
        for (int i = 1; i < progress.size(); i++) {
            assertTrue("进度应单调递增且不重复", progress.get(i) > progress.get(i - 1));
        }
    }

    @Test
    public void writeTo_loggingBuffer_isNotCounted() throws Exception {
        List<Integer> progress = new ArrayList<>();
        ProgressRequestBody body = new ProgressRequestBody(RequestBody.create(new byte[50_000], JPEG), progress::add);
        Buffer buffer = new Buffer();
        body.writeTo(buffer);
        assertEquals(50_000, buffer.size());
        assertTrue("调试日志写入 Buffer 时不统计进度", progress.isEmpty());
    }
}
