package com.heban.pestai.server.request;

import java.util.List;

import jakarta.validation.constraints.Size;

import com.heban.pestai.server.common.Rules;

/**
 * POST ai/sessions/{id}/messages：
 * <ul>
 *   <li>content 最多 500 字；带图时可以为空（只发图片），没有图片时不能为空（「请输入问题」，在 AiChatService 中校验）；</li>
 *   <li>image_urls 0–9 张，每张为 files/image 返回的完整地址（按用户选择的顺序，重复的地址只算一次）；</li>
 *   <li>image_url 为旧版 App 的单张图片地址：image_urls 为空时，非空的 image_url 视为 [image_url]；</li>
 *   <li>regenerate 可空。</li>
 * </ul>
 */
public class MessageRequest {

    @Size(max = 500, message = "问题最多 500 字")
    private String content;

    @Size(max = Rules.CHAT_IMAGES_MAX, message = Rules.CHAT_IMAGES_MESSAGE)
    private List<@Size(max = 255, message = "图片地址无效，请重新上传图片") String> imageUrls;

    /** 旧版 App（单张图片），新版 App 使用 imageUrls */
    @Size(max = 255, message = "图片地址无效，请重新上传图片")
    private String imageUrl;

    private Boolean regenerate;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Boolean getRegenerate() {
        return regenerate;
    }

    public void setRegenerate(Boolean regenerate) {
        this.regenerate = regenerate;
    }

    public boolean isRegenerateRequested() {
        return Boolean.TRUE.equals(regenerate);
    }
}
