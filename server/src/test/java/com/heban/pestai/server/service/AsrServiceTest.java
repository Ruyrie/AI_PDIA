package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.heban.pestai.server.client.AsrClient;
import com.heban.pestai.server.client.AsrException;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;

/** 语音识别：WAV 校验（格式、编码、时长、大小）、限流、上游失败与空结果的错误码。 */
class AsrServiceTest {

    private static final long USER_ID = 10001L;

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong now = new AtomicLong(1_000_000);

    private AsrService service(AsrClient client) {
        return new AsrService(client, new AiRateLimiter(20, now::get), Duration.ofSeconds(5));
    }

    private AsrClient returning(String text) {
        return new AsrClient() {
            @Override
            public String transcribe(byte[] wav, Duration timeout) {
                calls.incrementAndGet();
                return text;
            }

            @Override
            public boolean isMock() {
                return false;
            }
        };
    }

    @Test
    void validWavIsRecognizedAndTrimmed() {
        byte[] wav = WavAudio.silence(16000, 1000);
        assertThat(service(returning("  番茄叶子发黄是什么原因\n")).recognize(USER_ID, wav).text())
                .isEqualTo("番茄叶子发黄是什么原因");

        WavAudio.Info info = WavAudio.parse(wav);
        assertThat(info.sampleRate()).isEqualTo(16000);
        assertThat(info.channels()).isEqualTo(1);
        assertThat(info.bitsPerSample()).isEqualTo(16);
        assertThat(info.durationMillis()).isEqualTo(1000);
    }

    @Test
    void fillerOnlyResultIsTreatedAsNotHeard() {
        assertParam(() -> service(returning("嗯。")).recognize(USER_ID, WavAudio.silence(16000, 1000)), AsrService.NOT_HEARD);
        assertParam(() -> service(returning(" 啊， 嗯…… ")).recognize(USER_ID, WavAudio.silence(16000, 1000)),
                AsrService.NOT_HEARD);
        assertThat(service(returning("嗯，番茄怎么了")).recognize(USER_ID, WavAudio.silence(16000, 1000)).text())
                .isEqualTo("嗯，番茄怎么了");
        assertThat(AsrService.isFillerOnly("")).isTrue();
        assertThat(AsrService.isFillerOnly("好")).isFalse();
    }

    @Test
    void multipartIsReadAndEmptyOrOversizedRejected() {
        AsrService service = service(returning("你好"));
        MockMultipartFile audio = new MockMultipartFile("audio", "a.wav", "audio/wav", WavAudio.silence(16000, 300));
        assertThat(service.recognize(USER_ID, audio).text()).isEqualTo("你好");

        assertParam(() -> service.recognize(USER_ID, (org.springframework.web.multipart.MultipartFile) null), "请先录音");
        assertParam(() -> service.recognize(USER_ID, new MockMultipartFile("audio", new byte[0])), "请先录音");
        assertParam(() -> service.recognize(USER_ID, new byte[(int) AsrService.MAX_BYTES + 1]), "语音不能超过 2MB");
    }

    @Test
    void nonWavNonPcmAndBrokenFilesAre40001() {
        AsrService service = service(returning("x"));
        assertParam(() -> service.recognize(USER_ID, "ID3 mp3 data......".getBytes(StandardCharsets.US_ASCII)),
                WavAudio.NOT_WAV);
        assertParam(() -> service.recognize(USER_ID, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}), WavAudio.NOT_WAV);

        byte[] alaw = WavAudio.silence(8000, 100);
        alaw[20] = 6; // audio_format = 6（A-law）
        assertParam(() -> service.recognize(USER_ID, alaw), WavAudio.NOT_PCM);

        byte[] noData = new byte[36];
        System.arraycopy(WavAudio.silence(16000, 0), 0, noData, 0, 36);
        assertParam(() -> service.recognize(USER_ID, noData), WavAudio.BROKEN);
        assertThat(calls).hasValue(0);
    }

    @Test
    void extensiblePcmAndExtraChunksAreAccepted() {
        // fmt 为 WAVE_FORMAT_EXTENSIBLE（子格式 PCM），fmt 与 data 之间有一个 LIST 块
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + 40 + 8 + 4 + 8 + 3200).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(0).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(40)
                .putShort((short) 0xFFFE).putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2)
                .putShort((short) 16).putShort((short) 22).putShort((short) 16).putInt(4)
                .putShort((short) 1).put(new byte[14]);
        b.put("LIST".getBytes(StandardCharsets.US_ASCII)).putInt(4).put("INFO".getBytes(StandardCharsets.US_ASCII));
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(3200).put(new byte[3200]);
        WavAudio.Info info = WavAudio.parse(b.array());
        assertThat(info.durationMillis()).isEqualTo(100);
        assertThat(service(returning("好")).recognize(USER_ID, b.array()).text()).isEqualTo("好");
    }

    @Test
    void durationIsComputedFromHeaderAndLimitedTo60Seconds() {
        AsrService service = service(returning("x"));
        // 8kHz 16 位单声道 61 秒约 976KB，未超过 2MB：按文件头的 byte_rate 计算时长
        byte[] tooLong = WavAudio.silence(8000, 61_000);
        assertThat(tooLong.length).isLessThan((int) AsrService.MAX_BYTES);
        assertParam(() -> service.recognize(USER_ID, tooLong), "语音不能超过 60 秒");
        assertThat(service.recognize(USER_ID, WavAudio.silence(8000, 60_000)).text()).isEqualTo("x");

        // data 长度写得比实际大（边录边写未回填）：按实际字节计算
        byte[] streaming = WavAudio.silence(16000, 500);
        streaming[40] = (byte) 0xFF;
        streaming[41] = (byte) 0xFF;
        streaming[42] = (byte) 0xFF;
        streaming[43] = (byte) 0x7F;
        assertThat(WavAudio.parse(streaming).durationMillis()).isEqualTo(500);
        // 没有音频数据
        assertParam(() -> service.recognize(USER_ID, WavAudio.silence(16000, 0)), AsrService.NOT_HEARD);
    }

    @Test
    void emptyResultIs40001AndUpstreamFailureIs50201() {
        assertParam(() -> service(returning("  ")).recognize(USER_ID, WavAudio.silence(16000, 200)), AsrService.NOT_HEARD);
        assertParam(() -> service(returning(null)).recognize(USER_ID, WavAudio.silence(16000, 200)), AsrService.NOT_HEARD);

        AsrClient failing = new AsrClient() {
            @Override
            public String transcribe(byte[] wav, Duration timeout) throws AsrException {
                throw new AsrException("语音识别接口返回 HTTP 401");
            }

            @Override
            public boolean isMock() {
                return false;
            }
        };
        assertThatThrownBy(() -> service(failing).recognize(USER_ID, WavAudio.silence(16000, 200)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE);
                    assertThat(e.getMessage()).isEqualTo("语音识别暂不可用，请稍后再试");
                });
    }

    @Test
    void twentyRequestsPerMinutePerUser() {
        AsrService service = service(returning("x"));
        byte[] wav = WavAudio.silence(16000, 100);
        for (int i = 0; i < 20; i++) {
            service.recognize(USER_ID, wav);
        }
        assertThatThrownBy(() -> service.recognize(USER_ID, wav))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.TOO_FREQUENT);
        // 其他用户不受影响；无效文件不计数
        service.recognize(USER_ID + 1, wav);
        now.addAndGet(60_000);
        service.recognize(USER_ID, wav);
        assertThat(calls).hasValue(22);
    }

    private static void assertParam(ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }
}
