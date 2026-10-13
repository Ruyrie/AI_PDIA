package com.heban.pestai.server.client;

/**
 * AI 问答的系统提示（resources/prompts/chat-system.txt、chat-format.txt）与识别结论的固定开头。
 */
public final class ChatPrompts {

    /** 角色设定 */
    public static final String ROLE = VisionPrompts.load("prompts/chat-system.txt");

    /** 输出格式约定（与 App 端 AnswerFormatter 支持的格式一致） */
    public static final String FORMAT = VisionPrompts.load("prompts/chat-format.txt");

    /** 识别结论段落的开头，Mock 实现据此取出识别结论 */
    public static final String CONCLUSION_MARK = "本次对话针对的识别结论：";

    /** 历史中带图提问的文字说明 */
    public static final String IMAGE_NOTE = "（用户附带了一张图片）";

    private ChatPrompts() {
    }
}
