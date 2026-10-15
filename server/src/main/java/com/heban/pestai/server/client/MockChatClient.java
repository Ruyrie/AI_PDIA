package com.heban.pestai.server.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.heban.pestai.server.common.ChatRole;
import com.heban.pestai.server.config.PestaiProperties;

/**
 * 未配置 DEEPSEEK_API_KEY 时使用的问答模拟实现：先逐段回调一小段思考过程（模拟 DeepSeek 的 reasoning_content），
 * 再根据问题与识别结论生成一段固定的中文回答，按小片段逐段回调（每段间隔几十毫秒），模拟流式输出；支持取消。
 */
public class MockChatClient implements ChatClient {

    private static final int CHUNK_CODE_POINTS = 6;
    /** Mock 的模型名（记录到 t_ai_message.model / t_recognition.model） */
    public static final String MODEL_NAME = "mock";

    private final PestaiProperties.Mock mock;
    /** 是否模拟思考片段（识图模型的 Mock 委托本类时关闭：识图模型没有思考片段） */
    private final boolean reasoning;

    public MockChatClient(PestaiProperties.Mock mock) {
        this(mock, true);
    }

    public MockChatClient(PestaiProperties.Mock mock, boolean reasoning) {
        this.mock = mock;
        this.reasoning = reasoning;
    }

    @Override
    public void stream(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
            throws ChatClientException {
        if (reasoning && !emit(split(MockAnswers.reasoning(messages), CHUNK_CODE_POINTS), callback::onReasoning, control)) {
            return;
        }
        emit(split(MockAnswers.answer(messages), CHUNK_CODE_POINTS), callback::onDelta, control);
    }

    /** 逐段回调；被取消、客户端已断开或线程被中断时返回 false */
    private boolean emit(List<String> chunks, ChunkSink sink, StreamControl control) {
        for (String chunk : chunks) {
            if (control.isCancelled()) {
                return false;
            }
            try {
                sink.accept(chunk);
            } catch (IOException e) {
                control.cancel();
                return false;
            }
            if (!sleep(mock.getChatChunkDelayMs())) {
                return false;
            }
        }
        return !control.isCancelled();
    }

    @FunctionalInterface
    private interface ChunkSink {
        void accept(String chunk) throws IOException;
    }

    @Override
    public boolean isMock() {
        return true;
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    /** 按 Unicode 码点切片；换行单独保留在片段中 */
    static List<String> split(String text, int size) {
        List<String> chunks = new ArrayList<>();
        int[] codePoints = text.codePoints().toArray();
        for (int i = 0; i < codePoints.length; i += size) {
            int end = Math.min(codePoints.length, i + size);
            chunks.add(new String(codePoints, i, end - i));
        }
        return chunks;
    }

    /** 取最后一条用户消息 */
    static String lastUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (ChatRole.USER.equals(m.role())) {
                return m.text() == null ? "" : m.text();
            }
        }
        return "";
    }

    private static boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean supportsImages() {
        return true;
    }
}
