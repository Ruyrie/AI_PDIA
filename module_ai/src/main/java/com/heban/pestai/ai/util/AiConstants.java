package com.heban.pestai.ai.util;

import androidx.annotation.NonNull;

import com.heban.pestai.common.vo.SuggestionVO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 模块08 常量与 ViewModel 中使用的提示文案（ViewModel 不持有 Context，文案放在这里；界面文案在 strings.xml）。
 */
public final class AiConstants {

    /** 问题最多 500 字（与后端 @Size 一致） */
    public static final int MAX_QUESTION_LENGTH = 500;
    /** 大家常问最多显示 3 条 */
    public static final int SUGGESTION_COUNT = 3;
    /**
     * 只发图片不写文字时使用的问题。
     * <b>服务器依赖这句话逐字一致</b>：带图提问的文字为空或恰好等于这句话时按「没有文字」处理（会话标题为「图片提问」），
     * 打开历史对话时 App 也据此不显示文字气泡。不要修改。
     */
    public static final String DEFAULT_IMAGE_QUESTION = "请帮我看看这张照片里的作物有什么问题";

    /** 「追问结果」卡片：最近识别记录取 10 条（只列出识别成功的） */
    public static final int RECENT_RECORD_COUNT = 10;
    /** Q03 历史对话每页条数 */
    public static final int HISTORY_PAGE_SIZE = 20;

    public static final String TOAST_QUESTION_TOO_LONG = "问题最多 500 字";
    public static final String TOAST_IMAGE_READ_FAILED = "图片读取失败，请换一张试试";
    public static final String TOAST_IMAGE_UPLOAD_FAILED = "图片上传失败，点按重试";
    public static final String TOAST_SESSION_DELETED = "已删除";
    public static final String TOAST_DELETE_FAILED = "删除失败，请稍后再试";

    /** 「文字提问」卡片弹出的提问模板（点 Chip 填入输入框，不直接发送） */
    private static final String[] QUESTION_TEMPLATES = {
            "番茄叶片出现黄褐色斑点，是什么病？",
            "黄瓜苗期叶子发黄怎么办？",
            "蚜虫用什么药防治效果好？",
            "白菜软腐病怎么预防？",
            "果树开花期能打药吗？"
    };

    /** 内置的大家常问（游客或接口失败时使用），id 取负数与服务器 ID 区分 */
    private static final String[] DEFAULT_SUGGESTIONS = {
            "番茄叶片发黄卷曲是什么原因？",
            "白粉病和霜霉病怎么区分？",
            "蚜虫用什么办法防治效果好？"
    };

    private AiConstants() {
    }

    @NonNull
    public static List<String> questionTemplates() {
        return Collections.unmodifiableList(Arrays.asList(QUESTION_TEMPLATES));
    }

    @NonNull
    public static List<SuggestionVO> defaultSuggestions() {
        List<SuggestionVO> list = new ArrayList<>(DEFAULT_SUGGESTIONS.length);
        for (int i = 0; i < DEFAULT_SUGGESTIONS.length; i++) {
            SuggestionVO vo = new SuggestionVO();
            vo.setId(-(i + 1));
            vo.setText(DEFAULT_SUGGESTIONS[i]);
            list.add(vo);
        }
        return Collections.unmodifiableList(list);
    }
}
