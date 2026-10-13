package com.heban.pestai.ai.data;

import androidx.annotation.NonNull;

/**
 * 流式回答的一个片段：思考片段（{@code event: reasoning} 后紧随的 data 行）或正文片段（普通 data 行）。
 * 不可变；equals / hashCode 便于单元测试比较。
 */
public final class StreamChunk {

    /** 思考片段（DeepSeek reasoning_content） */
    public static final int REASONING = 1;
    /** 正文片段 */
    public static final int CONTENT = 2;

    public final int type;
    @NonNull
    public final String text;

    private StreamChunk(int type, @NonNull String text) {
        this.type = type;
        this.text = text;
    }

    @NonNull
    public static StreamChunk reasoning(@NonNull String text) {
        return new StreamChunk(REASONING, text);
    }

    @NonNull
    public static StreamChunk content(@NonNull String text) {
        return new StreamChunk(CONTENT, text);
    }

    public boolean isReasoning() {
        return type == REASONING;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StreamChunk)) {
            return false;
        }
        StreamChunk that = (StreamChunk) o;
        return type == that.type && text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return 31 * type + text.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return (type == REASONING ? "reasoning:" : "content:") + text;
    }
}
