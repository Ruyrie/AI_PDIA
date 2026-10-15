package com.heban.pestai.ai.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * POST ai/sessions/{id}/messages 请求体：content（不超过 500 字；带图时可以为空串）、
 * image_urls（files/image 返回的完整地址，最多 9 张，按顺序；没有图片时不输出）、regenerate。
 * 不再发送旧字段 image_url。
 */
public class SendMessageReq {

    private final String content;
    /** JSON：image_urls；没有图片时为 null（Gson 不输出） */
    @Nullable
    private final List<String> imageUrls;
    private final boolean regenerate;

    public SendMessageReq(@NonNull String content, @Nullable List<String> imageUrls, boolean regenerate) {
        this.content = content;
        this.imageUrls = imageUrls == null || imageUrls.isEmpty()
                ? null : Collections.unmodifiableList(new ArrayList<>(imageUrls));
        this.regenerate = regenerate;
    }

    public String getContent() {
        return content;
    }

    /** 没有图片时为空列表 */
    @NonNull
    public List<String> getImageUrls() {
        return imageUrls == null ? Collections.emptyList() : imageUrls;
    }

    public boolean isRegenerate() {
        return regenerate;
    }
}
