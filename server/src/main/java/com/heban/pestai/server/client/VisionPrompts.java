package com.heban.pestai.server.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import com.heban.pestai.server.common.Mode;

/**
 * 识图提示词（resources/prompts 下的文本文件）：PEST、CROP 两种模式各一份，要求模型只返回固定 JSON。
 */
public final class VisionPrompts {

    private static final String PEST = load("prompts/vision-pest.txt");
    private static final String CROP = load("prompts/vision-crop.txt");

    /** 识图时 user 消息中的文字指令 */
    public static final String USER_INSTRUCTION = "请识别这张照片，严格按要求只返回一个 JSON 对象。";

    private VisionPrompts() {
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
