package com.heban.pestai.server.client;

import java.time.Duration;

/** 未配置 DASHSCOPE_API_KEY 时使用的语音识别模拟实现：不论录音内容，都返回一句固定的问题，便于联调语音输入。 */
public class MockAsrClient implements AsrClient {

    public static final String MOCK_TEXT = "番茄叶子发黄是什么原因";

    @Override
    public String transcribe(byte[] wav, Duration timeout) {
        return MOCK_TEXT;
    }

    @Override
    public boolean isMock() {
        return true;
    }
}
