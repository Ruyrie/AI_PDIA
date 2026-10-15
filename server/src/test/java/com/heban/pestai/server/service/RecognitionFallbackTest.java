package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import com.heban.pestai.server.client.ChatClientException;
import com.heban.pestai.server.client.ChatMessage;
import com.heban.pestai.server.client.FallbackBreaker;
import com.heban.pestai.server.client.StreamCallback;
import com.heban.pestai.server.client.StreamControl;
import com.heban.pestai.server.client.VisionClient;
import com.heban.pestai.server.client.VisionException;
import com.heban.pestai.server.client.VisionFallback;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.FailReason;
import com.heban.pestai.server.common.Mode;
import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.config.VisionProperties;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.mapper.RecognitionMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.vo.RecognitionVO;

/**
 * 拍照识别的自动备用（主通义千问 → 备 DeepSeek）：主模型调用失败 / 超时 / 输出无法解析时改用备用模型，照片不清晰不改用；
 * 备用可用时主模型最多 15 秒、总时限仍为 25 秒；熔断 60 秒；开关关闭、没有 Key、主模型是 Mock 时与原来相同；
 * model / fallback 写入记录，App 看到的 model_name 不变。上游为假客户端，不访问网络、不连数据库。
 */
class RecognitionFallbackTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 10, 0);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 5, 6, 7, 8};
    private static final String VALID = """
            {"status":"SUCCESS","top_name":"黄瓜霜霉病","confidence":0.88,"severity":"中度","category":"卵菌病害",
             "part":"叶部","spread_fast":true,"evidence":"叶背有灰黑色霉层。","urgency_days":3,"others":[]}""";
    private static final String IMAGE_BAD = "{\"status\":\"IMAGE_BAD\"}";

    @TempDir
    Path tempDir;

    private final MutableClock clock = new MutableClock(NOW.atZone(ZONE).toInstant());
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicReference<String> deepseekKey = new AtomicReference<>("sk-deepseek-test");
    private RecognitionMapper mapper;
    private FileStorageService files;
    private FakeVision primary;
    private FakeVision fallback;
    private VisionProperties props;
    private VisionFallback visionFallback;
    private RecognitionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(RecognitionMapper.class);
        when(mapper.insert(any())).thenAnswer(inv -> {
            ((Recognition) inv.getArgument(0)).setId(3001L);
            return 1;
        });
        files = new FileStorageService(tempDir, "http://10.0.2.2:8080/", clock);
        primary = new FakeVision("qwen3.8-max");
        fallback = new FakeVision("deepseek-flash");
        props = new VisionProperties();
        props.setDisplayName("通义千问");
        service = newService(VisionFallback.PRIMARY_BUDGET);
    }

    private RecognitionService newService(Duration primaryBudget) {
        visionFallback = new VisionFallback(fallback,
                new FallbackBreaker(enabled::get, deepseekKey::get, clock, FallbackBreaker.OPEN_DURATION), primaryBudget);
        return new RecognitionService(mapper, files, primary, visionFallback, props, new RecognitionAssembler(files),
                mock(UserMapper.class), clock);
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("image", "leaf.jpg", "image/jpeg", JPEG);
    }

    private Recognition saved() {
        ArgumentCaptor<Recognition> captor = ArgumentCaptor.forClass(Recognition.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void primaryErrorFallsBackToDeepSeekWithSamePromptAndRecordsModel() {
        primary.script.add(Step.error(VisionException.Reason.UNAVAILABLE, "识图接口返回 HTTP 503：busy"));
        fallback.script.add(Step.result(VALID));

        RecognitionVO vo = service.recognize(10001L, image(), Mode.PEST);

        // App 看到的结果与平时相同：model_name 仍为「通义千问」，不出现备用模型
        assertThat(vo.topName()).isEqualTo("黄瓜霜霉病");
        assertThat(vo.modelName()).isEqualTo("通义千问");
        Recognition saved = saved();
        assertThat(saved.getStatus()).isEqualTo(RecognitionStatus.SUCCESS);
        assertThat(saved.getModelName()).isEqualTo("通义千问");
        assertThat(saved.getModel()).isEqualTo("deepseek-flash");
        assertThat(saved.getFallback()).isTrue();
        // 备用模型收到完全相同的输入（图片、模式、作物参考）
        assertThat(fallback.calls).hasSize(1);
        assertThat(fallback.calls.get(0).mode()).isEqualTo(Mode.PEST);
        assertThat(fallback.calls.get(0).image()).isEqualTo(JPEG);
        assertThat(fallback.calls.get(0).crops()).isEqualTo(primary.calls.get(0).crops());
        // 之后 60 秒熔断
        assertThat(visionFallback.breaker().isOpen()).isTrue();
    }

    @Test
    void primaryTimeoutFallsBackAndBudgetIsSplit15Plus10() {
        primary.script.add(Step.error(VisionException.Reason.TIMEOUT, "识图超时"));
        fallback.script.add(Step.result(VALID));

        service.recognize(1L, image(), Mode.CROP);

        // 备用可用：主模型最多 15 秒；主模型立即失败时备用模型可用剩余的全部时间（总时限 25 秒）
        Duration primaryBudget = primary.calls.get(0).timeout();
        assertThat(primaryBudget).isLessThanOrEqualTo(Duration.ofSeconds(15)).isGreaterThan(Duration.ofSeconds(14));
        Duration fallbackBudget = fallback.calls.get(0).timeout();
        assertThat(fallbackBudget).isLessThanOrEqualTo(Duration.ofSeconds(25)).isGreaterThan(Duration.ofSeconds(20));
        assertThat(saved().getFallback()).isTrue();
    }

    @Test
    void primaryUsingItsWholeBudgetLeavesTheRestOfTheTotalForTheFallback() {
        // 总时限 1 秒、主模型上限 300 ms：主模型用满自己的时间后超时，备用模型拿到剩余的约 700 ms
        props.setTimeoutSeconds(1);
        service = newService(Duration.ofMillis(300));
        primary.script.add(Step.sleepThenTimeout());
        fallback.script.add(Step.result(VALID));

        service.recognize(1L, image(), Mode.PEST);

        Duration primaryBudget = primary.calls.get(0).timeout();
        assertThat(primaryBudget).isLessThanOrEqualTo(Duration.ofMillis(300));
        Duration rest = fallback.calls.get(0).timeout();
        // 两者合计不超过总时限；备用模型拿到的是总时限减去主模型实际用掉的时间
        assertThat(primaryBudget.plus(rest)).isLessThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(rest).isLessThan(Duration.ofMillis(710)).isGreaterThan(Duration.ofMillis(500));
        assertThat(saved().getModel()).isEqualTo("deepseek-flash");
    }

    @Test
    void twoUnparseableOutputsFallBackButOneIsRetriedOnPrimaryFirst() {
        primary.script.add(Step.result("我觉得是霜霉病"));
        primary.script.add(Step.result("{\"status\":\"OK\"}"));
        fallback.script.add(Step.result(VALID));

        service.recognize(1L, image(), Mode.PEST);

        // 主模型先按原规则重试一次（共用 15 秒），仍无法解析才改用备用模型
        assertThat(primary.calls).hasSize(2);
        assertThat(primary.calls.get(1).timeout()).isLessThanOrEqualTo(primary.calls.get(0).timeout());
        assertThat(fallback.calls).hasSize(1);
        assertThat(saved().getFallback()).isTrue();
    }

    @Test
    void primaryRetrySucceedingDoesNotFallBack() {
        primary.script.add(Step.result("x"));
        primary.script.add(Step.result(VALID));

        service.recognize(1L, image(), Mode.PEST);

        assertThat(fallback.calls).isEmpty();
        assertThat(visionFallback.breaker().isOpen()).isFalse();
        assertThat(saved().getModel()).isEqualTo("qwen3.8-max");
    }

    @Test
    void imageBadFromThePrimaryIsAValidResultAndDoesNotFallBack() {
        primary.script.add(Step.result(IMAGE_BAD));

        assertThatThrownBy(() -> service.recognize(1L, image(), Mode.PEST))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.IMAGE_BAD);

        assertThat(fallback.calls).isEmpty();
        assertThat(visionFallback.breaker().isOpen()).isFalse();
        Recognition saved = saved();
        assertThat(saved.getStatus()).isEqualTo(RecognitionStatus.IMAGE_BAD);
        assertThat(saved.getModel()).isEqualTo("qwen3.8-max");
        assertThat(saved.getFallback()).isFalse();
    }

    @Test
    void imageBadFromTheFallbackIsRecordedWithTheFallbackModel() {
        primary.script.add(Step.error(VisionException.Reason.UNAVAILABLE, "down"));
        fallback.script.add(Step.result(IMAGE_BAD));

        assertThatThrownBy(() -> service.recognize(1L, image(), Mode.PEST))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.IMAGE_BAD);
        Recognition saved = saved();
        assertThat(saved.getStatus()).isEqualTo(RecognitionStatus.IMAGE_BAD);
        assertThat(saved.getModel()).isEqualTo("deepseek-flash");
        assertThat(saved.getFallback()).isTrue();
    }

    @Test
    void fallbackFailureReturnsItsReasonWithoutModel() {
        primary.script.add(Step.error(VisionException.Reason.UNAVAILABLE, "down"));
        fallback.script.add(Step.error(VisionException.Reason.TIMEOUT, "识图超时"));

        assertThatThrownBy(() -> service.recognize(1L, image(), Mode.PEST))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT);
        Recognition saved = saved();
        assertThat(saved.getStatus()).isEqualTo(RecognitionStatus.FAILED);
        assertThat(saved.getFailReason()).isEqualTo(FailReason.TIMEOUT);
        assertThat(saved.getModel()).isNull();
        assertThat(saved.getFallback()).isFalse();
        // 不再回到主模型
        assertThat(primary.calls).hasSize(1);
    }

    @Test
    void breakerSendsRequestsStraightToTheFallbackFor60SecondsThenTriesThePrimaryAgain() {
        primary.script.add(Step.error(VisionException.Reason.UNAVAILABLE, "down"));
        fallback.script.add(Step.result(VALID));
        service.recognize(1L, image(), Mode.PEST);
        assertThat(primary.calls).hasSize(1);

        // 59 秒后：直接使用备用模型，主模型不被调用；备用模型可用完整的 25 秒
        clock.advance(59_000);
        fallback.script.add(Step.result(VALID));
        service.recognize(1L, image(), Mode.PEST);
        assertThat(primary.calls).hasSize(1);
        assertThat(fallback.calls).hasSize(2);
        assertThat(fallback.calls.get(1).timeout()).isGreaterThan(Duration.ofSeconds(24));

        // 60 秒后：再试主模型，成功则不使用备用
        clock.advance(1_000);
        primary.script.add(Step.result(VALID));
        service.recognize(1L, image(), Mode.PEST);
        assertThat(primary.calls).hasSize(2);
        assertThat(fallback.calls).hasSize(2);
        ArgumentCaptor<Recognition> all = ArgumentCaptor.forClass(Recognition.class);
        verify(mapper, times(3)).insert(all.capture());
        assertThat(all.getAllValues()).extracting(Recognition::getModel)
                .containsExactly("deepseek-flash", "deepseek-flash", "qwen3.8-max");
        assertThat(all.getAllValues()).extracting(Recognition::getFallback).containsExactly(true, true, false);
    }

    @Test
    void noFallbackWhenSwitchedOffWithoutKeyOrWhenThePrimaryIsMock() {
        // 开关关闭：与原来完全相同（主模型独占 25 秒，失败即返回）
        enabled.set(false);
        primary.script.add(Step.error(VisionException.Reason.UNAVAILABLE, "down"));
        assertThatThrownBy(() -> service.recognize(1L, image(), Mode.PEST))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE);
        assertThat(primary.calls.get(0).timeout()).isGreaterThan(Duration.ofSeconds(24));

        // 没有 DEEPSEEK_API_KEY
        enabled.set(true);
        deepseekKey.set(" ");
        primary.script.add(Step.error(VisionException.Reason.UNAVAILABLE, "down"));
        assertThatThrownBy(() -> service.recognize(1L, image(), Mode.PEST)).isInstanceOf(BizException.class);

        // 主模型是 Mock
        deepseekKey.set("sk-deepseek-test");
        primary.mock = true;
        primary.script.add(Step.error(VisionException.Reason.TIMEOUT, "模拟识图超时"));
        assertThatThrownBy(() -> service.recognize(1L, image(), Mode.PEST)).isInstanceOf(BizException.class);

        assertThat(fallback.calls).isEmpty();
        assertThat(visionFallback.breaker().isOpen()).isFalse();
    }

    @Test
    void retryUsesTheFallbackAndUpdatesModelAndFallback() {
        String relative = files.save(JPEG, FileStorageService.REC);
        Recognition failed = new Recognition();
        failed.setId(2036L);
        failed.setUserId(1L);
        failed.setMode(Mode.PEST);
        failed.setStatus(RecognitionStatus.FAILED);
        failed.setImageUrl(relative);
        failed.setCreatedAt(NOW);
        Recognition fresh = new Recognition();
        fresh.setId(2036L);
        fresh.setMode(Mode.PEST);
        fresh.setStatus(RecognitionStatus.SUCCESS);
        fresh.setTopName("黄瓜霜霉病");
        fresh.setImageUrl(relative);
        when(mapper.selectByIdAndUser(2036L, 1L)).thenReturn(failed, fresh, failed);
        when(mapper.updateResult(any())).thenReturn(1);

        primary.script.add(Step.error(VisionException.Reason.UNAVAILABLE, "down"));
        fallback.script.add(Step.result(VALID));
        service.retry(1L, 2036L);
        ArgumentCaptor<Recognition> update = ArgumentCaptor.forClass(Recognition.class);
        verify(mapper).updateResult(update.capture());
        assertThat(update.getValue().getModel()).isEqualTo("deepseek-flash");
        assertThat(update.getValue().getFallback()).isTrue();
        assertThat(update.getValue().getModelName()).isEqualTo("通义千问");

        // 熔断中再次重新识别，备用模型判定照片不清晰：失败记录更新为照片不清晰，并记下备用模型
        fallback.script.add(Step.result(IMAGE_BAD));
        assertThatThrownBy(() -> service.retry(1L, 2036L)).isInstanceOf(BizException.class);
        verify(mapper).updateFailure(2036L, 1L, RecognitionStatus.IMAGE_BAD, FailReason.IMAGE_BAD, "deepseek-flash",
                true, NOW);
        assertThat(primary.calls).hasSize(1);
    }

    @Test
    void primarySuccessDoesNotTouchTheFallback() {
        primary.script.add(Step.result(VALID));
        service.recognize(1L, image(), Mode.PEST);
        assertThat(fallback.calls).isEmpty();
        Recognition saved = saved();
        assertThat(saved.getModel()).isEqualTo("qwen3.8-max");
        assertThat(saved.getFallback()).isFalse();
        // 备用可用时主模型也只有 15 秒
        assertThat(primary.calls.get(0).timeout()).isLessThanOrEqualTo(Duration.ofSeconds(15));
    }

    /** 一次调用的输入 */
    record Call(byte[] image, String mode, List<String> crops, Duration timeout) {
    }

    /** 脚本的一步：返回文本、抛出异常，或等到时限用完再超时 */
    record Step(String raw, VisionException.Reason reason, String message, boolean sleep) {

        static Step result(String raw) {
            return new Step(raw, null, null, false);
        }

        static Step error(VisionException.Reason reason, String message) {
            return new Step(null, reason, message, false);
        }

        static Step sleepThenTimeout() {
            return new Step(null, VisionException.Reason.TIMEOUT, "识图超时", true);
        }
    }

    /** 按脚本返回的假识图客户端，记录每次调用的输入 */
    static final class FakeVision implements VisionClient {

        final String model;
        final List<Step> script = new ArrayList<>();
        final List<Call> calls = new ArrayList<>();
        boolean mock;

        FakeVision(String model) {
            this.model = model;
        }

        @Override
        public String recognize(byte[] image, String mimeType, String mode, List<String> userCrops, Duration timeout)
                throws VisionException {
            calls.add(new Call(image, mode, userCrops, timeout));
            if (script.isEmpty()) {
                throw new AssertionError(model + " 不应被调用");
            }
            Step step = script.remove(0);
            if (step.sleep()) {
                try {
                    // 用满时限（toMillis 向下取整，多睡 1 ms）
                    Thread.sleep(timeout.toMillis() + 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (step.reason() != null) {
                throw new VisionException(step.reason(), step.message());
            }
            return step.raw();
        }

        @Override
        public void streamChat(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
                throws ChatClientException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isMock() {
            return mock;
        }

        @Override
        public String modelName() {
            return mock ? "mock" : model;
        }
    }

    /** 可手动推进的时钟（熔断计时用；识别的时间预算按 System.nanoTime） */
    static final class MutableClock extends Clock {

        private final AtomicLong millis;

        MutableClock(Instant start) {
            this.millis = new AtomicLong(start.toEpochMilli());
        }

        void advance(long delta) {
            millis.addAndGet(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }
}
