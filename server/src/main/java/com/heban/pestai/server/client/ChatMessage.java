package com.heban.pestai.server.client;

import com.heban.pestai.server.common.ChatRole;

/**
 * 发给大模型的一条消息。imageDataUrl 只在带图提问的当前问题上有值（data:image/jpeg;base64,...）。
 */
public record ChatMessage(String role, String text, String imageDataUrl) {

    public static ChatMessage system(String text) {
        return new ChatMessage(ChatRole.SYSTEM, text, null);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(ChatRole.USER, text, null);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(ChatRole.ASSISTANT, text, null);
    }

    public ChatMessage withImage(String dataUrl) {
        return new ChatMessage(role, text, dataUrl);
    }

    public boolean hasImage() {
        return imageDataUrl != null && !imageDataUrl.isEmpty();
    }
}
