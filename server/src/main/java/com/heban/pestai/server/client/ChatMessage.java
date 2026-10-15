package com.heban.pestai.server.client;

import java.util.List;

import com.heban.pestai.server.common.ChatRole;

/**
 * 发给大模型的一条消息。imageDataUrls 只在带图提问的当前问题上有值（按用户选择的顺序，每张为
 * data:image/jpeg;base64,...）；其余消息为空列表（不为 null，不可修改）。
 */
public record ChatMessage(String role, String text, List<String> imageDataUrls) {

    public ChatMessage {
        imageDataUrls = imageDataUrls == null ? List.of() : List.copyOf(imageDataUrls);
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(ChatRole.SYSTEM, text, List.of());
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(ChatRole.USER, text, List.of());
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(ChatRole.ASSISTANT, text, List.of());
    }

    /** 只带这一张图片（拍照识别、单图提问） */
    public ChatMessage withImage(String dataUrl) {
        return withImages(List.of(dataUrl));
    }

    /** 带这些图片（替换原有图片，顺序不变） */
    public ChatMessage withImages(List<String> dataUrls) {
        return new ChatMessage(role, text, dataUrls);
    }

    public boolean hasImage() {
        return !imageDataUrls.isEmpty();
    }
}
