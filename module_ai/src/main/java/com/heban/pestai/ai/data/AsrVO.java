package com.heban.pestai.ai.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * POST ai/asr（语音识别）返回的 data：{@code {"text": "番茄叶子发黄怎么办"}}。
 * 没识别出文字时服务器返回 40001「没有听清，请再说一遍」，不会返回空 text；App 仍按空串兜底提示。
 */
public class AsrVO {

    /** 识别出的文字 */
    @Nullable
    private String text;

    @Nullable
    public String getText() {
        return text;
    }

    public void setText(@Nullable String text) {
        this.text = text;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AsrVO)) {
            return false;
        }
        return Objects.equals(text, ((AsrVO) o).text);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(text);
    }

    @NonNull
    @Override
    public String toString() {
        return "AsrVO{text='" + text + "'}";
    }
}
