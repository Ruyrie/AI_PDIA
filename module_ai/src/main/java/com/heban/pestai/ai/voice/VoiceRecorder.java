package com.heban.pestai.ai.voice;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * 一次录音：后台线程中用 AudioRecord（麦克风、16kHz、单声道、PCM 16 位）采集，边录边写入 WAV（{@link WavWriter}）。
 * <ul>
 *     <li>{@link #start} 立即启动录音线程；每读到约 50ms 的采样计算一次音量，回调 {@link Listener#onLevel}；</li>
 *     <li>{@link #stop()}：结束采集、回填 WAV 文件头，回调 {@link Listener#onFinished}（文件交给调用方，由其负责删除）；</li>
 *     <li>{@link #cancel()}：结束采集并删除文件，之后不再有任何回调；</li>
 *     <li>无法初始化 / 启动（麦克风被占用、没有权限）或读写出错时删除文件并回调 {@link Listener#onError}；</li>
 *     <li>AudioRecord 的创建、读取、释放都在录音线程中完成，线程结束前一定 stop + release，不会让麦克风一直开着；</li>
 *     <li>超过 60 秒的数据不再写入（{@link WavWriter} 上限），保证文件不超过后端的 2MB。</li>
 * </ul>
 * 回调都在主线程；{@link #stop()} / {@link #cancel()} 只在主线程调用。
 */
public final class VoiceRecorder {

    /** 录音回调（主线程） */
    public interface Listener {
        /** 当前音量 0 ~ 1（约每 50ms 一次） */
        void onLevel(float level);

        /** 录音完成，WAV 已写好 */
        void onFinished(@NonNull File wav);

        /** 无法录音或录音出错（文件已删除） */
        void onError();
    }

    private static final String TAG = "VoiceRecorder";
    /** 每次读取 50ms 的采样（800 个） */
    private static final int READ_SAMPLES = WavWriter.SAMPLE_RATE / 20;
    /** 录音目录中超过 10 分钟的残留文件（进程被杀等情况）在下次录音时清理 */
    private static final long STALE_MS = 10 * 60 * 1000L;

    @NonNull
    private final File file;
    @NonNull
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 采集循环继续 */
    private volatile boolean running = true;
    /** 已取消：删除文件、不再回调（主线程写，录音线程与主线程读） */
    private volatile boolean cancelled;

    private VoiceRecorder(@NonNull File file, @NonNull Listener listener) {
        this.file = file;
        this.listener = listener;
    }

    /**
     * 立即在后台线程开始录音。调用前须已获得 RECORD_AUDIO 权限（没有权限时回调 onError）。
     *
     * @param file 输出的 WAV 文件（目录不存在时自动创建）
     */
    @MainThread
    @NonNull
    public static VoiceRecorder start(@NonNull File file, @NonNull Listener listener) {
        VoiceRecorder recorder = new VoiceRecorder(file, listener);
        Thread thread = new Thread(recorder::run, "ai-voice-recorder");
        thread.start();
        return recorder;
    }

    /** 结束录音：写好 WAV 后回调 onFinished（取消过则不回调） */
    @MainThread
    public void stop() {
        running = false;
    }

    /** 取消录音：删除文件，不再回调；可在 stop() 之后调用（还没回调 onFinished 时一并取消） */
    @MainThread
    public void cancel() {
        cancelled = true;
        running = false;
    }

    // ==================== 录音线程 ====================

    /** 已在调用方检查权限；没有权限时 AudioRecord 初始化失败或抛出 SecurityException，按 onError 处理 */
    @SuppressLint("MissingPermission")
    private void run() {
        deleteStaleFiles(file.getParentFile(), file);
        AudioRecord record = null;
        WavWriter writer = null;
        boolean ok = false;
        try {
            int minBuffer = AudioRecord.getMinBufferSize(WavWriter.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) {
                throw new IOException("不支持的录音参数：" + minBuffer);
            }
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, WavWriter.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuffer, READ_SAMPLES * WavWriter.BLOCK_ALIGN) * 2);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("AudioRecord 初始化失败");
            }
            writer = WavWriter.open(file, WavWriter.bytesFor(VoiceGesture.MAX_DURATION_MS));
            if (running) {
                record.startRecording();
                if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IOException("麦克风被占用");
                }
            }
            short[] buffer = new short[READ_SAMPLES];
            while (running) {
                int read = record.read(buffer, 0, buffer.length);
                if (read < 0) {
                    throw new IOException("读取录音失败：" + read);
                }
                if (read == 0) {
                    continue;
                }
                // 超过 60 秒的部分丢弃（界面会在 60 秒时自动结束）
                writer.write(buffer, read);
                float level = VoiceLevel.of(buffer, read);
                main.post(() -> {
                    if (!cancelled && running) {
                        listener.onLevel(level);
                    }
                });
            }
            ok = true;
        } catch (Exception e) {
            Log.w(TAG, "录音失败", e);
        } finally {
            if (record != null) {
                try {
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop();
                    }
                } catch (IllegalStateException ignored) {
                    // 已停止
                }
                record.release();
            }
        }
        finish(writer, ok);
    }

    /** 录音线程：写好文件头后交给主线程；取消或出错时删除文件 */
    private void finish(@Nullable WavWriter writer, boolean ok) {
        if (cancelled || !ok || writer == null) {
            if (writer != null) {
                writer.abort();
            } else {
                deleteQuietly(file);
            }
            if (!cancelled) {
                main.post(() -> {
                    if (!cancelled) {
                        listener.onError();
                    }
                });
            }
            return;
        }
        try {
            writer.finish();
        } catch (IOException e) {
            Log.w(TAG, "写入录音失败", e);
            writer.abort();
            main.post(() -> {
                if (!cancelled) {
                    listener.onError();
                }
            });
            return;
        }
        main.post(() -> {
            if (cancelled) {
                deleteQuietly(file);
            } else {
                listener.onFinished(file);
            }
        });
    }

    /** 清理目录中超过 10 分钟的残留录音（不删除本次的文件） */
    private static void deleteStaleFiles(@Nullable File dir, @NonNull File current) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (!f.equals(current) && f.isFile() && now - f.lastModified() > STALE_MS) {
                deleteQuietly(f);
            }
        }
    }

    static void deleteQuietly(@NonNull File file) {
        //noinspection ResultOfMethodCallIgnored 临时文件，删除失败由系统清理缓存目录
        file.delete();
    }
}
