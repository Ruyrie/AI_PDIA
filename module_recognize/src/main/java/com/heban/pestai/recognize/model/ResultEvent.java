package com.heban.pestai.recognize.model;

/**
 * R01 一次性事件。跳转所需参数由 Activity 从 ResultViewModel 读取。
 */
public enum ResultEvent {
    /** 追问 AI（from_chat 为 true 时直接返回原对话） */
    ASK_AI,
    /** 识别记录 R03 */
    OPEN_HISTORY,
    /** 记录不是识别成功状态，转到 R02 */
    GO_ERROR,
    /** 记录不存在时点「返回」 */
    CLOSE
}
