package com.heban.pestai.server.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST ai/sessions/{id}/messages：content 1–500 字，image_url 可空（files/image 返回的地址），regenerate 可空 */
public class MessageRequest {

    @NotBlank(message = "请输入问题")
    @Size(max = 500, message = "问题最多 500 字")
    private String content;

    @Size(max = 255)
    private String imageUrl;

    private Boolean regenerate;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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
