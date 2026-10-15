package com.heban.pestai.server.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 后台登录按 IP 的失败计数（内存，单机部署，重启后清零）：同一 IP 连续输错 5 次锁定 15 分钟；登录成功清零。
 * 最后一次失败已超过 15 分钟且未锁定的记录视为过期（重新计数），并在记录较多时顺便清理，避免内存无限增长。
 * 按账号的锁定保存在 t_admin（failed_attempts、locked_until），见 AdminAuthService。
 */
@Component
public class LoginAttemptLimiter {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final int CLEANUP_THRESHOLD = 1000;

    private record Attempts(int failures, Instant lastFailure, Instant lockedUntil) {
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    /** 该 IP 仍处于锁定中时返回剩余时长，否则返回 null */
    public Duration lockRemaining(String ip, Instant now) {
        Attempts a = attempts.get(ip);
        if (a == null || a.lockedUntil() == null || !a.lockedUntil().isAfter(now)) {
            return null;
        }
        return Duration.between(now, a.lockedUntil());
    }

    /** 记一次失败；这次失败使该 IP 进入锁定时返回 true */
    public boolean recordFailure(String ip, Instant now) {
        if (attempts.size() > CLEANUP_THRESHOLD) {
            cleanup(now);
        }
        Attempts updated = attempts.compute(ip, (key, old) -> {
            // 接着上一次计数：上次没有锁定（锁定过的到期后重新计数）且上次失败在 15 分钟内
            boolean continuing = old != null && old.lockedUntil() == null
                    && old.lastFailure().plus(LOCK_DURATION).isAfter(now);
            int failures = continuing ? old.failures() + 1 : 1;
            if (failures >= MAX_FAILURES) {
                return new Attempts(0, now, now.plus(LOCK_DURATION));
            }
            return new Attempts(failures, now, null);
        });
        return updated.lockedUntil() != null;
    }

    /** 登录成功：清除该 IP 的失败记录 */
    public void reset(String ip) {
        attempts.remove(ip);
    }

    void cleanup(Instant now) {
        attempts.entrySet().removeIf(e -> {
            Attempts a = e.getValue();
            boolean locked = a.lockedUntil() != null && a.lockedUntil().isAfter(now);
            return !locked && !a.lastFailure().plus(LOCK_DURATION).isAfter(now);
        });
    }

    int size() {
        return attempts.size();
    }
}
