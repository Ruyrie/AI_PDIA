package com.heban.pestai.server.client;

import java.time.Duration;

/**
 * 语音识别客户端（通义千问 ASR，默认 qwen3-asr-flash）。未配置 DASHSCOPE_API_KEY 时为 MockAsrClient。
 * 音频只用于本次识别，不保存。
 */
public interface AsrClient {

    /**
     * 识别一段 WAV 录音（调用方已校验为 RIFF/WAVE PCM、≤ 2MB、≤ 60 秒），返回识别出的文字（可能为空串）。
     *
     * @throws AsrException 上游 HTTP 错误、超时、返回内容无法解析
     */
    String transcribe(byte[] wav, Duration timeout) throws AsrException;

    /** 是否为 Mock 实现 */
    boolean isMock();
}
