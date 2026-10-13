package com.heban.pestai.ai.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * POST ai/sessions/{id}/messages 请求体：content（1–500 字）、image_url（可空，为空时不输出）、regenerate。
 */
public class SendMessageReq {

    private final String content;
    /** JSON：image_url */
    @Nullable
    private final String imageUrl;
    private final boolean regenerate;

    public SendMessageReq(@NonNull String content, @Nullable String imageUrl, boolean regenerate) {
        this.content = content;
        this.imageUrl = imageUrl;
        this.regenerate = regenerate;
    }

    public String getContent() {
        return content;
    }

    @Nullable
    public String getImageUrl() {
        return imageUrl;
    }

    public boolean isRegenerate() {
        return regenerate;
    }
}
