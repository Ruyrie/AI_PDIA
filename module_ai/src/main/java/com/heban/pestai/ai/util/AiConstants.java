package com.heban.pestai.ai.util;

/**
 * 模块08 常量与 ViewModel 中使用的提示文案（ViewModel 不持有 Context，文案放在这里；界面文案在 strings.xml）。
 */
public final class AiConstants {

    /** 问题最多 500 字（与后端 @Size 一致） */
    public static final int MAX_QUESTION_LENGTH = 500;
    /**
     * 旧版本只发图片不写文字时使用的问题（现在只发图片时 content 为空串，不再发送这句话）。
     * <b>服务器依赖这句话逐字一致</b>：带图提问的文字为空或恰好等于这句话时按「没有文字」处理（会话标题为「图片提问」），
     * 打开历史对话时 App 也据此不显示旧数据的文字气泡。不要修改。
     */
    public static final String DEFAULT_IMAGE_QUESTION = "请帮我看看这张照片里的作物有什么问题";
    /** 一次提问最多 9 张图片（与后端 image_urls 上限一致） */
    public static final int MAX_IMAGES = 9;
    /** 输入栏图片同时上传的数量，其余排队等待 */
    public static final int MAX_CONCURRENT_UPLOADS = 3;

    /** Q03 历史对话每页条数 */
    public static final int HISTORY_PAGE_SIZE = 20;

    public static final String TOAST_QUESTION_TOO_LONG = "问题最多 500 字";
    public static final String TOAST_IMAGE_READ_FAILED = "图片读取失败，请换一张试试";
    public static final String TOAST_IMAGE_UPLOAD_FAILED = "图片上传失败，点按重试";
    public static final String TOAST_TOO_MANY_IMAGES = "最多只能添加 9 张图片";
    public static final String TOAST_IMAGES_UPLOADING = "图片还在上传，请稍候";
    public static final String TOAST_IMAGES_FAILED = "有图片上传失败，请点按重试或移除";
    public static final String TOAST_SESSION_DELETED = "已删除";
    public static final String TOAST_DELETE_FAILED = "删除失败，请稍后再试";

    /** 语音输入：录音不足 1 秒 */
    public static final String TOAST_VOICE_TOO_SHORT = "说话时间太短";
    /** 语音输入：没识别出文字（服务器 40001 的文案一致） */
    public static final String TOAST_VOICE_NOT_HEARD = "没有听清，请再说一遍";
    /** 语音输入：识别服务不可用（服务器 50201 的文案一致） */
    public static final String TOAST_VOICE_UNAVAILABLE = "语音识别暂不可用，请稍后再试";
    /** 语音输入：麦克风被占用 / 无法初始化 */
    public static final String TOAST_MIC_UNAVAILABLE = "麦克风暂时无法使用，请稍后再试";
    /** 语音输入：按住「按住 说话」时申请的麦克风权限已授予（本次没有录音，需要重新按住） */
    public static final String TOAST_MIC_GRANTED = "已开启麦克风，请按住说话";
    /** 语音输入：拒绝麦克风权限（点按麦克风图标时拒绝则留在文字输入） */
    public static final String TOAST_MIC_DENIED = "需要麦克风权限才能语音输入，可在系统设置中开启";

    private AiConstants() {
    }
}
