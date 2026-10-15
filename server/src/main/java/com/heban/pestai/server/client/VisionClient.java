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
     * @param image     图片字节
     * @param mimeType  image/jpeg 或 image/png
     * @param mode      PEST / CROP
     * @param userCrops 用户的主要作物（已按 UserProfile 清洗，可为空列表）：真实模型把它作为一行参考信息附在识图指令后
     *                  （见 VisionPrompts.userInstruction），Mock 实现忽略
     * @param timeout   本次调用的时限（总预算 25 秒中剩余的部分）
     */
    String recognize(byte[] image, String mimeType, String mode, List<String> userCrops, Duration timeout)
            throws VisionException;

    /** 带图问答（流式）：当前问题的 ChatMessage 携带本轮全部图片的 data 地址（最多 9 张）；返回格式与 ChatClient 相同 */
    void streamChat(List<ChatMessage> messages, StreamCallback callback, StreamControl control) throws ChatClientException;

    /** 是否为 Mock 实现 */
    boolean isMock();

    /** 本次调用将使用的模型名（记录到 t_recognition.model、t_ai_message.model）；Mock 为 "mock"，未知为 null */
    default String modelName() {
        return null;
    }
}
