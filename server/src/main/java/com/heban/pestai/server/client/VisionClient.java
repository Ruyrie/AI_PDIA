package com.heban.pestai.server.client;

import java.time.Duration;
import java.util.List;

/**
 * 识图模型客户端（通义千问 VL）。未配置 DASHSCOPE_API_KEY 时为 MockVisionClient。
 */
public interface VisionClient {

    /**
     * 识别照片，返回模型输出的原始文本（按提示词应为固定结构的 JSON，由 VisionResultParser 解析校验）。
     *
     * @param image    图片字节
     * @param mimeType image/jpeg 或 image/png
     * @param mode     PEST / CROP
     * @param timeout  本次调用的时限（总预算 25 秒中剩余的部分）
     */
    String recognize(byte[] image, String mimeType, String mode, Duration timeout) throws VisionException;

    /** 带图问答（流式）：当前问题的 ChatMessage 携带图片 data 地址；返回格式与 ChatClient 相同 */
    void streamChat(List<ChatMessage> messages, StreamCallback callback, StreamControl control) throws ChatClientException;

    /** 是否为 Mock 实现 */
    boolean isMock();
}
