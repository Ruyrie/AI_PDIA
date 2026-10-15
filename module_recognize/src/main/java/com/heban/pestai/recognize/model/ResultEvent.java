package com.heban.pestai.recognize.model;

/**
 * R01 一次性事件。跳转所需参数由 Activity 从 ResultViewModel 读取。
 */
public enum ResultEvent {
    /** 追问 AI（from_chat 为 true 时直接返回原对话） */
    ASK_AI,
    /** 识别记录 R03 */
    OPEN_HISTORY,
    /** 「继续识别」：R01 正下方就是同一模式的 S01（S01 → S02 → R01），关闭 R01 回到相机 */
    BACK_TO_SCAN,
    /** 「继续识别」：其他入口，以本记录的模式打开 S01（返回栈中已有 S01 时清掉其上的页面）并关闭 R01 */
    OPEN_SCAN,
    /** 记录不是识别成功状态，转到 R02 */
    GO_ERROR,
    /** 记录不存在时点「返回」 */
    CLOSE
}
