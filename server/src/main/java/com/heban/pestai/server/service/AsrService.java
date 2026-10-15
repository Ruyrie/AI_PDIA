package com.heban.pestai.server.service;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.heban.pestai.server.client.AsrClient;
import com.heban.pestai.server.client.AsrException;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.config.AsrProperties;
import com.heban.pestai.server.vo.AsrVO;

/**
 * App 语音输入（docs/后台管理接口.md「六、App 语音识别接口」）：校验录音 → 限流 → 调用语音识别 → 返回文字。
 * <ul>
 *   <li>录音：multipart 字段 audio，RIFF/WAVE PCM（App 录 16 位、16kHz、单声道），≤ 2MB，按文件头计算时长 ≤ 60 秒
 *       （留 0.5 秒余量，录音结束的几十毫秒误差不算超时）；不符合返回 40001；</li>
 *   <li>限流：每个用户 60 秒内最多 20 次（只计校验通过、真正调用识别的请求），超出 42901；</li>
 *   <li>识别失败（上游错误、超时）50201「语音识别暂不可用，请稍后再试」；没有识别出文字 40001「没有听清，请再说一遍」。</li>
 * </ul>
 * 音频只在内存中用于本次识别，不写磁盘、不入库。
 */
@Service
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);

    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final long MAX_DURATION_MILLIS = 60_000;
    /** 时长余量：录音机停止时多写的几十毫秒不算超过 60 秒 */
    static final long DURATION_TOLERANCE_MILLIS = 500;

    public static final String NOT_HEARD = "没有听清，请再说一遍";
    /** 只有语气词和标点（静音或环境噪声时模型常输出「嗯。」）：按没有听清处理 */
    private static final Pattern FILLER_ONLY = Pattern.compile("[\\s\\p{P}嗯啊哦呃额唔噢喔哎呀]*");
    public static final String UNAVAILABLE = "语音识别暂不可用，请稍后再试";
    static final String AUDIO_REQUIRED = "请先录音";
    static final String TOO_LARGE = "语音不能超过 2MB";
    static final String TOO_LONG = "语音不能超过 60 秒";

    private final AsrClient asrClient;
    private final AiRateLimiter rateLimiter;
    private final Duration timeout;

    @Autowired
    public AsrService(AsrClient asrClient, AsrProperties properties) {
        this(asrClient, new AiRateLimiter(properties.getMaxRequestsPerMinute(), System::currentTimeMillis),
                Duration.ofSeconds(properties.getTimeoutSeconds()));
    }

    AsrService(AsrClient asrClient, AiRateLimiter rateLimiter, Duration timeout) {
        this.asrClient = asrClient;
        this.rateLimiter = rateLimiter;
        this.timeout = timeout;
    }

    public AsrVO recognize(long userId, MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw BizException.param(AUDIO_REQUIRED);
        }
        if (audio.getSize() > MAX_BYTES) {
            throw BizException.param(TOO_LARGE);
        }
        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (IOException e) {
            throw BizException.param(WavAudio.BROKEN);
        }
        return recognize(userId, bytes);
    }

    AsrVO recognize(long userId, byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw BizException.param(TOO_LARGE);
        }
        WavAudio.Info info = WavAudio.parse(bytes);
        if (info.durationMillis() > MAX_DURATION_MILLIS + DURATION_TOLERANCE_MILLIS) {
            throw BizException.param(TOO_LONG);
        }
        if (info.dataBytes() == 0) {
            throw BizException.param(NOT_HEARD);
        }
        rateLimiter.acquire(userId);

        long started = System.nanoTime();
        String text;
        try {
            text = asrClient.transcribe(bytes, timeout);
        } catch (AsrException e) {
            log.warn("语音识别失败（用户 {}，时长 {} ms）：{}", userId, info.durationMillis(), e.getMessage());
            throw new BizException(ErrorCode.AI_UNAVAILABLE, UNAVAILABLE);
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        String trimmed = text == null ? "" : text.strip();
        log.info("语音识别完成：用户 {}，时长 {} ms，{} 字，耗时 {} ms", userId, info.durationMillis(),
                trimmed.codePointCount(0, trimmed.length()), elapsed);
        if (isFillerOnly(trimmed)) {
            throw BizException.param(NOT_HEARD);
        }
        return new AsrVO(trimmed);
    }

    /** 空串，或只有语气词与标点 */
    static boolean isFillerOnly(String text) {
        return FILLER_ONLY.matcher(text).matches();
    }
}
