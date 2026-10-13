package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import com.heban.pestai.server.client.VisionClient;
import com.heban.pestai.server.client.VisionException;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.FailReason;
import com.heban.pestai.server.common.Mode;
import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.config.VisionProperties;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.mapper.RecognitionMapper;
import com.heban.pestai.server.request.RecordPageRequest;
import com.heban.pestai.server.vo.PageVO;
import com.heban.pestai.server.vo.RecognitionVO;
import com.heban.pestai.server.vo.RecordItemVO;

class RecognitionServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 14, 20, 5);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
    private static final String VALID = """
            {"status":"SUCCESS","top_name":"黄瓜霜霉病","confidence":0.92,"severity":"中度","category":"卵菌病害",
             "part":"叶部","spread_fast":true,"evidence":"叶背有灰黑色霉层。","urgency_days":3,
             "others":[{"name":"黄瓜细菌性角斑病","confidence":0.06}]}""";

    @TempDir
    Path tempDir;

    private RecognitionMapper mapper;
    private VisionClient visionClient;
    private FileStorageService files;
    private RecognitionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(RecognitionMapper.class);
        visionClient = mock(VisionClient.class);
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        files = new FileStorageService(tempDir, "http://10.0.2.2:8080/", clock);
        VisionProperties props = new VisionProperties();
        props.setDisplayName("通义千问");
        service = new RecognitionService(mapper, files, visionClient, props, new RecognitionAssembler(files), clock);
        when(mapper.insert(any())).thenAnswer(inv -> {
            ((Recognition) inv.getArgument(0)).setId(2036L);
            return 1;
        });
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("image", "leaf.jpg", "image/jpeg", JPEG);
    }

    @Test
    void successfulRecognitionIsSavedAndReturned() throws Exception {
        when(visionClient.recognize(any(), eq("image/jpeg"), eq(Mode.PEST), any())).thenReturn(VALID);

        RecognitionVO vo = service.recognize(10001L, image(), Mode.PEST);

        assertThat(vo.id()).isEqualTo(2036L);
        assertThat(vo.status()).isEqualTo(RecognitionStatus.SUCCESS);
        assertThat(vo.topName()).isEqualTo("黄瓜霜霉病");
        assertThat(vo.confidence().toPlainString()).isEqualTo("0.92");
        assertThat(vo.spreadFast()).isTrue();
        assertThat(vo.others()).hasSize(1);
        assertThat(vo.modelName()).isEqualTo("通义千问");
        assertThat(vo.createdAt()).isEqualTo(NOW);
        assertThat(vo.imageUrl()).startsWith("http://10.0.2.2:8080/files/rec/20261013/").endsWith(".jpg");

        ArgumentCaptor<Recognition> saved = ArgumentCaptor.forClass(Recognition.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(10001L);
        assertThat(saved.getValue().getImageUrl()).startsWith("rec/20261013/");
        assertThat(saved.getValue().getOthersJson()).contains("黄瓜细菌性角斑病");
        assertThat(files.exists(saved.getValue().getImageUrl())).isTrue();
    }

    @Test
    void invalidJsonIsRetriedOnceThenSucceeds() throws Exception {
        when(visionClient.recognize(any(), anyString(), anyString(), any())).thenReturn("我觉得是霜霉病", VALID);

        RecognitionVO vo = service.recognize(1L, image(), Mode.PEST);

        assertThat(vo.topName()).isEqualTo("黄瓜霜霉病");
        verify(visionClient, times(2)).recognize(any(), anyString(), anyString(), any());
    }

    @Test
    void twoInvalidResultsAreRecordedAsUnavailable() throws Exception {
        when(visionClient.recognize(any(), anyString(), anyString(), any())).thenReturn("{}", "{\"status\":\"OK\"}");

        assertThatThrownBy(() -> service.recognize(1L, image(), Mode.PEST))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_UNAVAILABLE);

        ArgumentCaptor<Recognition> saved = ArgumentCaptor.forClass(Recognition.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RecognitionStatus.FAILED);
        assertThat(saved.getValue().getFailReason()).isEqualTo(FailReason.UNAVAILABLE);
        assertThat(saved.getValue().getTopName()).isNull();
    }

    @Test
    void timeoutIsRecordedAndReturns50401WithoutRetry() throws Exception {
        when(visionClient.recognize(any(), anyString(), anyString(), any()))
                .thenThrow(new VisionException(VisionException.Reason.TIMEOUT, "timeout"));

        assertThatThrownBy(() -> service.recognize(1L, image(), Mode.CROP))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_TIMEOUT);

        verify(visionClient, times(1)).recognize(any(), anyString(), anyString(), any());
        ArgumentCaptor<Recognition> saved = ArgumentCaptor.forClass(Recognition.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getFailReason()).isEqualTo(FailReason.TIMEOUT);
        assertThat(saved.getValue().getMode()).isEqualTo(Mode.CROP);
    }

    @Test
    void blurryPhotoIsRecordedAsImageBadAndReturns42201() throws Exception {
        when(visionClient.recognize(any(), anyString(), anyString(), any())).thenReturn("{\"status\":\"IMAGE_BAD\"}");

        assertThatThrownBy(() -> service.recognize(1L, image(), Mode.PEST))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.IMAGE_BAD);

        ArgumentCaptor<Recognition> saved = ArgumentCaptor.forClass(Recognition.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RecognitionStatus.IMAGE_BAD);
        assertThat(saved.getValue().getFailReason()).isEqualTo(FailReason.IMAGE_BAD);
    }

    @Test
    void visionCallsShareThe25SecondBudget() throws Exception {
        ArgumentCaptor<Duration> budgets = ArgumentCaptor.forClass(Duration.class);
        when(visionClient.recognize(any(), anyString(), anyString(), budgets.capture())).thenReturn("x", VALID);
        service.recognize(1L, image(), Mode.PEST);
        assertThat(budgets.getAllValues().get(0)).isLessThanOrEqualTo(Duration.ofSeconds(25));
        assertThat(budgets.getAllValues().get(1)).isLessThanOrEqualTo(budgets.getAllValues().get(0));
    }

    @Test
    void nonImageUploadIsRejectedBeforeCallingTheModel() throws Exception {
        MockMultipartFile fake = new MockMultipartFile("image", "a.jpg", "image/jpeg", "not an image".getBytes());
        assertThatThrownBy(() -> service.recognize(1L, fake, Mode.PEST))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);
        verify(visionClient, never()).recognize(any(), anyString(), anyString(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void retryOfSuccessfulRecordKeepsOldResultWhenModelFails() throws Exception {
        String relative = files.save(JPEG, FileStorageService.REC);
        Recognition existing = record(RecognitionStatus.SUCCESS, relative);
        when(mapper.selectByIdAndUser(2036L, 1L)).thenReturn(existing);
        when(visionClient.recognize(any(), anyString(), anyString(), any()))
                .thenThrow(new VisionException(VisionException.Reason.UNAVAILABLE, "down"));

        assertThatThrownBy(() -> service.retry(1L, 2036L))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_UNAVAILABLE);
        verify(mapper, never()).updateFailure(anyLong(), anyLong(), anyString(), anyString(), any());
        verify(mapper, never()).updateResult(any());
    }

    @Test
    void retryOfFailedRecordUpdatesOnlyFailReason() throws Exception {
        String relative = files.save(JPEG, FileStorageService.REC);
        when(mapper.selectByIdAndUser(2036L, 1L)).thenReturn(record(RecognitionStatus.FAILED, relative));
        when(visionClient.recognize(any(), anyString(), anyString(), any())).thenReturn("{\"status\":\"IMAGE_BAD\"}");

        assertThatThrownBy(() -> service.retry(1L, 2036L))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.IMAGE_BAD);
        verify(mapper).updateFailure(2036L, 1L, RecognitionStatus.IMAGE_BAD, FailReason.IMAGE_BAD, NOW);
    }

    @Test
    void retrySuccessOverwritesResultAndReturnsFreshRecord() throws Exception {
        String relative = files.save(JPEG, FileStorageService.REC);
        Recognition failed = record(RecognitionStatus.FAILED, relative);
        Recognition fresh = record(RecognitionStatus.SUCCESS, relative);
        fresh.setTopName("黄瓜霜霉病");
        when(mapper.selectByIdAndUser(2036L, 1L)).thenReturn(failed, fresh);
        when(mapper.updateResult(any())).thenReturn(1);
        when(visionClient.recognize(any(), anyString(), anyString(), any())).thenReturn(VALID);

        RecognitionVO vo = service.retry(1L, 2036L);

        assertThat(vo.id()).isEqualTo(2036L);
        assertThat(vo.topName()).isEqualTo("黄瓜霜霉病");
        ArgumentCaptor<Recognition> update = ArgumentCaptor.forClass(Recognition.class);
        verify(mapper).updateResult(update.capture());
        assertThat(update.getValue().getUserId()).isEqualTo(1L);
        assertThat(update.getValue().getModelName()).isEqualTo("通义千问");
    }

    @Test
    void retryWithMissingPhotoOrForeignRecordIs40001() {
        when(mapper.selectByIdAndUser(5L, 1L)).thenReturn(null);
        assertThatThrownBy(() -> service.retry(1L, 5L))
                .hasMessage(RecognitionService.RECORD_NOT_FOUND);

        when(mapper.selectByIdAndUser(6L, 1L)).thenReturn(record(RecognitionStatus.FAILED, "rec/20260101/missing.jpg"));
        assertThatThrownBy(() -> service.retry(1L, 6L))
                .hasMessage(RecognitionService.IMAGE_MISSING);
    }

    @Test
    void deleteIsIdempotentAndDoesNotRevealOtherUsersRecords() {
        Recognition mine = new Recognition();
        mine.setUserId(1L);
        mine.setDeleted(1);
        when(mapper.selectOwnerById(10L)).thenReturn(mine);
        service.delete(1L, 10L);
        verify(mapper, never()).logicDelete(anyLong(), anyLong(), any());

        Recognition others = new Recognition();
        others.setUserId(2L);
        others.setDeleted(0);
        when(mapper.selectOwnerById(11L)).thenReturn(others);
        assertThatThrownBy(() -> service.delete(1L, 11L)).hasMessage(RecognitionService.DELETE_NOT_FOUND);

        Recognition alive = new Recognition();
        alive.setUserId(1L);
        alive.setDeleted(0);
        when(mapper.selectOwnerById(12L)).thenReturn(alive);
        service.delete(1L, 12L);
        verify(mapper).logicDelete(12L, 1L, NOW);
    }

    @Test
    void pagingComputesHasMoreAndSkipsQueryBeyondTotal() {
        when(mapper.countByUser(1L, null)).thenReturn(45L);
        when(mapper.selectPageByUser(eq(1L), isNull(), eq(20L), eq(20)))
                .thenReturn(java.util.List.of(record(RecognitionStatus.SUCCESS, "rec/a.jpg")));
        RecordPageRequest page2 = new RecordPageRequest();
        page2.setPage(2);
        page2.setMode("");
        PageVO<RecordItemVO> result = service.page(1L, page2);
        assertThat(result.total()).isEqualTo(45L);
        assertThat(result.hasMore()).isTrue();

        RecordPageRequest page9 = new RecordPageRequest();
        page9.setPage(9);
        PageVO<RecordItemVO> empty = service.page(1L, page9);
        assertThat(empty.list()).isEmpty();
        assertThat(empty.hasMore()).isFalse();
        verify(mapper, times(1)).selectPageByUser(anyLong(), any(), anyLong(), anyInt());
    }

    private static Recognition record(String status, String imageUrl) {
        Recognition r = new Recognition();
        r.setId(2036L);
        r.setUserId(1L);
        r.setMode(Mode.PEST);
        r.setStatus(status);
        r.setImageUrl(imageUrl);
        r.setCreatedAt(NOW.minusDays(1));
        r.setFailReason(RecognitionStatus.SUCCESS.equals(status) ? null : FailReason.TIMEOUT);
        return r;
    }
}
