package com.heban.pestai.server.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.config.PestaiProperties;

/**
 * AI 提问限流：每个用户 60 秒滑动窗口内最多 N 次（默认 10，重新生成也计数），超出返回 42901。
 * 服务单机部署，计数保存在内存中，重启后清零。语音识别（AsrService）另建一个实例，每人每分钟 20 次，与提问分开计数。
 */
@Component
public class AiRateLimiter {

    static final long WINDOW_MILLIS = 60_000;

    private final int maxPerWindow;
    private final LongSupplier nowMillis;
    private final Map<Long, Deque<Long>> requests = new ConcurrentHashMap<>();

    @Autowired
    public AiRateLimiter(PestaiProperties properties) {
        this(properties.getAi().getMaxQuestionsPerMinute(), System::currentTimeMillis);
    }

    AiRateLimiter(int maxPerWindow, LongSupplier nowMillis) {
        this.maxPerWindow = maxPerWindow;
        this.nowMillis = nowMillis;
    }

    public void acquire(long userId) {
        long now = nowMillis.getAsLong();
        Deque<Long> queue = requests.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() >= WINDOW_MILLIS) {
                queue.pollFirst();
            }
            if (queue.size() >= maxPerWindow) {
                throw new BizException(ErrorCode.TOO_FREQUENT);
            }
            queue.addLast(now);
        }
    }
}
