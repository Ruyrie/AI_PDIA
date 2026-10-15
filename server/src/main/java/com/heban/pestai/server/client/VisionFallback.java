package com.heban.pestai.server.client;

import java.time.Clock;
import java.time.Duration;

/**
 * 拍照识别的自动备用（docs/后台管理接口.md「八、模型自动备用」）：备用识图客户端（DeepSeekVisionClient，VISION_FALLBACK_MODEL）、
 * 熔断器与主模型的时间上限。改用备用模型的判定与时间预算的分配在 RecognitionService.analyze 中：
 * <ul>
 *   <li>{@link #available} 为 false（开关关闭、DEEPSEEK_API_KEY 未配置、主模型是 Mock）时与原来完全相同：主模型独占 25 秒；</li>
 *   <li>为 true 时主模型（含解析失败的重试）限制在 {@link #primaryBudget()}（15 秒）内，剩余时间（约 10 秒）留给备用模型；</li>
 *   <li>主模型失败（HTTP 错误、超时、输出无法解析）才改用备用模型；模型正常返回的「照片不清晰」不改用；</li>
 *   <li>熔断中（主模型 60 秒内失败过）直接使用备用模型，备用模型可用完整的 25 秒。</li>
 * </ul>
 */
public class VisionFallback {

    /** 默认的主模型时间上限（备用可用时） */
    public static final Duration PRIMARY_BUDGET = Duration.ofSeconds(15);

    private final VisionClient client;
    private final FallbackBreaker breaker;
    private final Duration primaryBudget;

    public VisionFallback(VisionClient client, FallbackBreaker breaker, Duration primaryBudget) {
        this.client = client;
        this.breaker = breaker;
        this.primaryBudget = primaryBudget;
    }

    /** 不使用自动备用（单元测试） */
    public static VisionFallback disabled() {
        return new VisionFallback(null,
                new FallbackBreaker(() -> false, () -> "", Clock.systemUTC(), FallbackBreaker.OPEN_DURATION),
                PRIMARY_BUDGET);
    }

    /** 本次识别能否改用备用模型：开关打开、DEEPSEEK_API_KEY 已配置、主模型不是 Mock */
    public boolean available(VisionClient primary) {
        return client != null && breaker.available(primary.isMock());
    }

    /** 备用识图客户端（DeepSeek） */
    public VisionClient client() {
        return client;
    }

    public FallbackBreaker breaker() {
        return breaker;
    }

    /** 备用可用时主模型（含重试）最多使用的时间 */
    public Duration primaryBudget() {
        return primaryBudget;
    }
}
