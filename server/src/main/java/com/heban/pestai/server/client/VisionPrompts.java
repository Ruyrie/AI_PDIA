package com.heban.pestai.server.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.heban.pestai.server.common.Mode;

/**
 * 识图提示词（resources/prompts 下的文本文件）：PEST、CROP 两种模式各一份，要求模型只返回固定 JSON。
 */
public final class VisionPrompts {

    private static final String PEST = load("prompts/vision-pest.txt");
    private static final String CROP = load("prompts/vision-crop.txt");

    /** 识图时 user 消息中的文字指令 */
    public static final String USER_INSTRUCTION = "请识别这张照片，严格按要求只返回一个 JSON 对象。";

    /** 用户填写了主要作物时，另起一行附在文字指令后的参考信息（{crops} 替换为顿号分隔的作物名）；种植身份不用于识图 */
    static final String CROPS_HINT = "参考信息：该用户主要种植{crops}。仅当照片中的作物种类难以判断时参考；"
            + "照片能明确看出作物时以照片为准，不要因为这条信息改变判断。";

    private VisionPrompts() {
    }

    /**
     * 识图时 user 消息中的文字：没有作物时就是 USER_INSTRUCTION；有作物时（PEST、CROP 两种模式相同）另起一行附参考信息。
     * 只多一行短文字，JSON 输出约定仍由系统提示规定，不影响 25 秒的时间预算。
     *
     * @param userCrops 已按 UserProfile 清洗的作物（可为 null 或空列表）
     */
    public static String userInstruction(List<String> userCrops) {
        if (userCrops == null || userCrops.isEmpty()) {
            return USER_INSTRUCTION;
        }
        return USER_INSTRUCTION + "\n" + CROPS_HINT.replace("{crops}", String.join("、", userCrops));
    }

    public static String systemPrompt(String mode) {
        return Mode.CROP.equals(mode) ? CROP : PEST;
    }

    public static String load(String path) {
        try (InputStream in = VisionPrompts.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("缺少提示词文件：" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("读取提示词失败：" + path, e);
        }
    }
}
