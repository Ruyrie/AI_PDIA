package com.heban.pestai.ai.data;

import java.io.IOException;

/**
 * 流式回答没有收到 data: [DONE] 就断开（服务端异常、Nginx 超时等）。
 * 与断网、读取超时一样属于 IOException，界面统一显示「回答中断，点按重试」。
 */
public class StreamBrokenException extends IOException {

    public StreamBrokenException() {
        super("stream closed before [DONE]");
    }
}
