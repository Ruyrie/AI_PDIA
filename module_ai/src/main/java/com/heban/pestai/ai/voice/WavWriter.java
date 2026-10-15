package com.heban.pestai.ai.voice;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 把录音写成 WAV 文件（纯 Java，便于单元测试）：固定 PCM 16 位、16kHz、单声道，即后端 POST ai/asr 要求的格式。
 * <ul>
 *     <li>{@link #open(File, long)} 先写 44 字节文件头（两处长度暂为 0），之后 {@link #write(short[], int)} 追加小端序采样；</li>
 *     <li>{@link #finish()} 回填 RIFF 块长度（36 + 数据长度，偏移 4）与 data 块长度（偏移 40）并关闭文件；</li>
 *     <li>数据超过上限（60 秒 = 1,920,000 字节，加文件头不到 2MB）的部分直接丢弃，保证上传大小不超限；</li>
 *     <li>{@link #abort()} 关闭并删除文件（取消、录音太短、出错时）。</li>
 * </ul>
 * 只在录音线程中使用，不是线程安全的。
 */
public final class WavWriter implements Closeable {

    public static final int SAMPLE_RATE = 16_000;
    public static final int CHANNELS = 1;
    public static final int BITS_PER_SAMPLE = 16;
    /** 每个采样帧的字节数（单声道 16 位为 2） */
    public static final int BLOCK_ALIGN = CHANNELS * BITS_PER_SAMPLE / 8;
    /** 每秒字节数：16000 × 2 = 32000 */
    public static final int BYTE_RATE = SAMPLE_RATE * BLOCK_ALIGN;
    public static final int HEADER_SIZE = 44;

    /** RIFF 块长度字段的偏移 */
    static final int RIFF_SIZE_OFFSET = 4;
    /** data 块长度字段的偏移 */
    static final int DATA_SIZE_OFFSET = 40;

    @NonNull
    private final File file;
    @NonNull
    private final RandomAccessFile out;
    private final long maxDataBytes;
    private long dataBytes;
    private byte[] buffer = new byte[0];
    private boolean closed;

    private WavWriter(@NonNull File file, @NonNull RandomAccessFile out, long maxDataBytes) {
        this.file = file;
        this.out = out;
        this.maxDataBytes = maxDataBytes;
    }

    /**
     * 创建（覆盖）文件并写入文件头。
     *
     * @param maxDataBytes 最多写入的 PCM 字节数（不含文件头），超出的采样丢弃
     */
    @NonNull
    public static WavWriter open(@NonNull File file, long maxDataBytes) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建目录：" + dir);
        }
        RandomAccessFile out = new RandomAccessFile(file, "rw");
        try {
            out.setLength(0);
            out.write(header(0));
        } catch (IOException e) {
            closeQuietly(out);
            throw e;
        }
        return new WavWriter(file, out, Math.max(0, maxDataBytes - maxDataBytes % BLOCK_ALIGN));
    }

    /**
     * 追加 count 个 16 位采样（按小端序写入）。
     *
     * @return false 表示已达到上限，本次有采样被丢弃
     */
    public boolean write(@NonNull short[] samples, int count) throws IOException {
        if (closed) {
            throw new IOException("WAV 文件已关闭");
        }
        int n = Math.max(0, Math.min(count, samples.length));
        long room = (maxDataBytes - dataBytes) / BLOCK_ALIGN;
        int accepted = (int) Math.min(n, room);
        if (accepted > 0) {
            int bytes = accepted * BLOCK_ALIGN;
            if (buffer.length < bytes) {
                buffer = new byte[bytes];
            }
            for (int i = 0; i < accepted; i++) {
                short s = samples[i];
                buffer[i * 2] = (byte) (s & 0xFF);
                buffer[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            out.write(buffer, 0, bytes);
            dataBytes += bytes;
        }
        return accepted == n;
    }

    /** 已写入的 PCM 字节数（不含文件头） */
    public long getDataBytes() {
        return dataBytes;
    }

    @NonNull
    public File getFile() {
        return file;
    }

    /**
     * 回填文件头中的两处长度并关闭文件。
     *
     * @return 写入的 PCM 字节数
     */
    public long finish() throws IOException {
        if (closed) {
            throw new IOException("WAV 文件已关闭");
        }
        try {
            out.seek(RIFF_SIZE_OFFSET);
            out.write(intLE(36 + dataBytes));
            out.seek(DATA_SIZE_OFFSET);
            out.write(intLE(dataBytes));
        } finally {
            closed = true;
            out.close();
        }
        return dataBytes;
    }

    /** 关闭并删除文件（不抛异常，可重复调用） */
    public void abort() {
        if (!closed) {
            closed = true;
            closeQuietly(out);
        }
        //noinspection ResultOfMethodCallIgnored 临时文件，删除失败由系统清理缓存目录
        file.delete();
    }

    /** 只关闭、不回填长度（异常路径兜底；正常结束用 {@link #finish()}） */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            closeQuietly(out);
        }
    }

    // ==================== 纯计算 ====================

    /**
     * PCM 16 位 / 16kHz / 单声道的 44 字节文件头。
     *
     * @param dataBytes data 块长度（PCM 字节数）
     */
    @NonNull
    public static byte[] header(long dataBytes) {
        byte[] h = new byte[HEADER_SIZE];
        putAscii(h, 0, "RIFF");
        putIntLE(h, RIFF_SIZE_OFFSET, 36 + dataBytes);
        putAscii(h, 8, "WAVE");
        putAscii(h, 12, "fmt ");
        putIntLE(h, 16, 16);
        // AudioFormat = 1（PCM）、声道数
        putShortLE(h, 20, 1);
        putShortLE(h, 22, CHANNELS);
        putIntLE(h, 24, SAMPLE_RATE);
        putIntLE(h, 28, BYTE_RATE);
        putShortLE(h, 32, BLOCK_ALIGN);
        putShortLE(h, 34, BITS_PER_SAMPLE);
        putAscii(h, 36, "data");
        putIntLE(h, DATA_SIZE_OFFSET, dataBytes);
        return h;
    }

    /** 时长对应的 PCM 字节数（按采样帧对齐）：60 秒为 1,920,000 */
    public static long bytesFor(long durationMs) {
        long bytes = Math.max(0, durationMs) * BYTE_RATE / 1000;
        return bytes - bytes % BLOCK_ALIGN;
    }

    /** PCM 字节数对应的时长（毫秒） */
    public static long durationMs(long dataBytes) {
        return Math.max(0, dataBytes) * 1000 / BYTE_RATE;
    }

    @NonNull
    private static byte[] intLE(long value) {
        byte[] b = new byte[4];
        putIntLE(b, 0, value);
        return b;
    }

    private static void putIntLE(@NonNull byte[] b, int offset, long value) {
        b[offset] = (byte) (value & 0xFF);
        b[offset + 1] = (byte) ((value >> 8) & 0xFF);
        b[offset + 2] = (byte) ((value >> 16) & 0xFF);
        b[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void putShortLE(@NonNull byte[] b, int offset, int value) {
        b[offset] = (byte) (value & 0xFF);
        b[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void putAscii(@NonNull byte[] b, int offset, @NonNull String text) {
        for (int i = 0; i < text.length(); i++) {
            b[offset + i] = (byte) text.charAt(i);
        }
    }

    private static void closeQuietly(@NonNull RandomAccessFile out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // 关闭失败不影响结果
        }
    }
}
