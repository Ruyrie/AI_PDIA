package com.heban.pestai.server.client;

import java.time.Clock;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 模型自动备用的公共部分（docs/后台管理接口.md「八、模型自动备用」），问答、识图各一个实例：
 * <ul>
 *   <li>能否改用备用模型（{@link #available}）：开关 AI_FALLBACK_ENABLED 打开、备用模型的 Key 已配置、主模型不是 Mock，三者都满足；
 *       开关与 Key 每次调用时读取，后台修改后立即生效；</li>
 *   <li>熔断：主模型失败（失败的判定由调用方负责，只在确实改用了备用模型时记录）后 60 秒内的请求直接交给备用模型，不再先试主模型；
 *       60 秒后自动恢复为先试主模型，再失败则重新计时。状态只保存在内存中（单机部署），重启后恢复为先试主模型；</li>
 *   <li>失败原因摘要（{@link #summary}）：写日志用，压缩空白、遮盖疑似密钥的片段（sk-…、Bearer …），最多 160 字。</li>
 * </ul>
 */
public class FallbackBreaker {

    /** 默认熔断时长 */
    public static final Duration OPEN_DURATION = Duration.ofSeconds(60);
    static final int SUMMARY_MAX = 160;
    /** 疑似密钥：sk- 开头的令牌、Authorization 头中的 Bearer 令牌 */
    private static final Pattern SECRET = Pattern.compile("(?i)(sk-|bearer\\s+)[A-Za-z0-9._~+/=-]{4,}");

    private final BooleanSupplier enabled;
    private final Supplier<String> fallbackKey;
    private final Clock clock;
    private final long openMillis;
    /** 熔断到该时间（clock 毫秒）；0 表示未熔断 */
    private volatile long openUntilMillis;

    public FallbackBreaker(BooleanSupplier enabled, Supplier<String> fallbackKey, Clock clock, Duration openDuration) {
        this.enabled = enabled;
        this.fallbackKey = fallbackKey;
        this.clock = clock;
        this.openMillis = openDuration.toMillis();
    }

    /** 本次能否改用备用模型：开关打开、备用模型的 Key 已配置、主模型不是 Mock */
    public boolean available(boolean primaryIsMock) {
        if (primaryIsMock || !enabled.getAsBoolean()) {
            return false;
        }
        String key = fallbackKey.get();
        return key != null && !key.isBlank();
    }

    /** 主模型是否处于熔断中（最近 60 秒内失败过）：熔断中的请求直接交给备用模型 */
    public boolean isOpen() {
        return clock.millis() < openUntilMillis;
    }

    /** 记录一次主模型失败：从现在起熔断 60 秒（熔断中再次记录则重新计时） */
    public void recordFailure() {
        openUntilMillis = clock.millis() + openMillis;
    }

    /** 熔断剩余的秒数（向上取整，未熔断为 0），只用于日志 */
    public long remainingSeconds() {
        long remaining = openUntilMillis - clock.millis();
        return remaining <= 0 ? 0 : (remaining + 999) / 1000;
    }

    /** 失败原因摘要：压缩空白、遮盖疑似密钥的片段、最多 160 字；为空时返回「没有说明」 */
    public static String summary(String message) {
        if (message == null || message.isBlank()) {
            return "没有说明";
        }
        String s = message.replaceAll("\\s+", " ").trim();
        s = SECRET.matcher(s).replaceAll("$1****");
        return s.length() > SUMMARY_MAX ? s.substring(0, SUMMARY_MAX) + "…" : s;
    }
}
