package com.heban.pestai.server.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.heban.pestai.server.common.Identity;

/**
 * AI 问答的系统提示（resources/prompts/chat-system.txt、chat-profile.txt、chat-format.txt）与识别结论的固定开头。
 */
public final class ChatPrompts {

    /** 角色设定 */
    public static final String ROLE = VisionPrompts.load("prompts/chat-system.txt");

    /** 输出格式约定（与 App 端 AnswerFormatter 支持的格式一致） */
    public static final String FORMAT = VisionPrompts.load("prompts/chat-format.txt");

    /** 用户画像段落的开头（单独一行），其后每行一项：身份对应的回答侧重点、主要作物 */
    public static final String PROFILE_MARK = "用户画像：";

    /** chat-profile.txt 中主要作物一行的键 */
    static final String CROPS_KEY = "主要作物";

    /** 主要作物一行中替换为作物名的占位符 */
    static final String CROPS_PLACEHOLDER = "{crops}";

    /** 用户画像（prompts/chat-profile.txt）：键为三种种植身份与「主要作物」 */
    private static final Map<String, String> PROFILE = loadProfile("prompts/chat-profile.txt");

    /** 识别结论段落的开头，Mock 实现据此取出识别结论 */
    public static final String CONCLUSION_MARK = "本次对话针对的识别结论：";

    /** 历史中带图提问的文字说明（1 张图片） */
    public static final String IMAGE_NOTE = "（用户附带了一张图片）";

    /** 历史中只发图片、没有文字的提问（1 张图片）的文字说明 */
    public static final String IMAGE_ONLY_NOTE = "（用户发送了一张图片）";

    /** 当前问题只有 1 张图片、没有文字时发给模型的问题（与旧版 App 的默认图片提问一致） */
    public static final String IMAGE_QUESTION = "请帮我看看这张照片里的作物有什么问题";

    /** 当前问题有多张图片、没有文字时发给模型的问题 */
    public static final String IMAGES_QUESTION = "请帮我看看这些照片里的作物有什么问题";

    /** 历史中带图提问附加在文字后的说明：1 张为「（用户附带了一张图片）」，N 张为「（用户附带了 N 张图片）」 */
    public static String imageNote(int count) {
        return count == 1 ? IMAGE_NOTE : "（用户附带了 " + count + " 张图片）";
    }

    /** 历史中只发图片的提问的文字：1 张为「（用户发送了一张图片）」，N 张为「（用户发送了 N 张图片）」 */
    public static String imageOnlyNote(int count) {
        return count == 1 ? IMAGE_ONLY_NOTE : "（用户发送了 " + count + " 张图片）";
    }

    /** 当前问题只有图片、没有文字时发给模型的问题（按图片张数） */
    public static String imageQuestion(int count) {
        return count > 1 ? IMAGES_QUESTION : IMAGE_QUESTION;
    }

    /** 种植身份对应的回答侧重点（「用户是种植户（……）。给田间可操作的办法……」）；为空或不认识的身份返回 null */
    public static String identityGuide(String identity) {
        return identity == null || CROPS_KEY.equals(identity) ? null : PROFILE.get(identity);
    }

    /** 「用户主要种植：番茄、黄瓜、小麦。问题没有说明作物时，可优先结合这些作物回答；……」（作物须已清洗） */
    public static String cropsLine(List<String> crops) {
        return PROFILE.get(CROPS_KEY).replace(CROPS_PLACEHOLDER, String.join("、", crops));
    }

    /** 读取「键=内容」格式的用户画像提示词（# 开头为注释）；缺少任一项时启动失败 */
    static Map<String, String> loadProfile(String path) {
        Map<String, String> map = new HashMap<>();
        for (String raw : VisionPrompts.load(path).lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalStateException("用户画像提示词格式不正确（应为 键=内容）：" + line);
            }
            map.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        for (String key : List.of(Identity.GROWER, Identity.GARDENER, Identity.TECHNICIAN, CROPS_KEY)) {
            String value = map.get(key);
            if (value == null || value.isEmpty()) {
                throw new IllegalStateException("用户画像提示词缺少「" + key + "」：" + path);
            }
        }
        if (!map.get(CROPS_KEY).contains(CROPS_PLACEHOLDER)) {
            throw new IllegalStateException("用户画像提示词「" + CROPS_KEY + "」缺少占位符 " + CROPS_PLACEHOLDER + "：" + path);
        }
        return Map.copyOf(map);
    }

    private ChatPrompts() {
    }
}
