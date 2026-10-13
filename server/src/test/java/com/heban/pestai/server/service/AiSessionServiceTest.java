package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ChatRole;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiSession;
import com.heban.pestai.server.entity.AiSessionSummary;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;
import com.heban.pestai.server.mapper.RecognitionMapper;
import com.heban.pestai.server.vo.PageVO;
import com.heban.pestai.server.vo.SessionDetailVO;
import com.heban.pestai.server.vo.SessionItemVO;

/** 历史对话列表、打开历史对话、删除对话（Mapper 全部 Mock，图片写在临时目录）。 */
class AiSessionServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 14, 23, 0);
    private static final long USER_ID = 10001L;
    private static final long OTHER_USER = 10002L;
    private static final String BASE = "http://10.0.2.2:8080";

    @TempDir
    Path root;

    private AiSessionMapper sessions;
    private AiMessageMapper messages;
    private RecognitionMapper recognitions;
    private FileStorageService files;
    private AiSessionService service;

    @BeforeEach
    void setUp() {
        sessions = mock(AiSessionMapper.class);
        messages = mock(AiMessageMapper.class);
        recognitions = mock(RecognitionMapper.class);
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        files = new FileStorageService(root, BASE, clock);
        service = new AiSessionService(sessions, messages, recognitions, new RecognitionAssembler(files), files, clock);
    }

    // ---------------------------------------------------------------- 列表

    @Test
    void pageMapsSummariesToItemsWithPreviewAndDefaultTitle() {
        when(sessions.countWithMessagesByUser(USER_ID)).thenReturn(3L);
        AiSessionSummary untitled = summary(512L, null, null, 1, ChatRole.USER, "番茄叶片发黄卷曲是什么原因？", null);
        AiSessionSummary followUp = summary(511L, 2036L, "追问：黄瓜霜霉病", 4, ChatRole.ASSISTANT,
                "**处理建议**\n1. 上午通风排湿\n- 摘除病叶", null);
        when(sessions.selectPageWithMessages(USER_ID, 0L, 2)).thenReturn(List.of(untitled, followUp));

        PageVO<SessionItemVO> page = service.page(USER_ID, 1, 2);

        assertThat(page.total()).isEqualTo(3L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.list()).hasSize(2);
        SessionItemVO first = page.list().get(0);
        assertThat(first.sessionId()).isEqualTo(512L);
        assertThat(first.recordId()).isNull();
        assertThat(first.title()).isEqualTo("新对话");
        assertThat(first.lastMessage()).isEqualTo("番茄叶片发黄卷曲是什么原因？");
        assertThat(first.messageCount()).isEqualTo(1);
        assertThat(first.updatedAt()).isEqualTo(NOW);
        SessionItemVO second = page.list().get(1);
        assertThat(second.recordId()).isEqualTo(2036L);
        assertThat(second.title()).isEqualTo("追问：黄瓜霜霉病");
        assertThat(second.lastMessage()).isEqualTo("处理建议 1. 上午通风排湿 摘除病叶");
        assertThat(second.messageCount()).isEqualTo(4);
    }

    @Test
    void pageOffsetHasMoreAndEmptyPages() {
        when(sessions.countWithMessagesByUser(USER_ID)).thenReturn(3L);
        when(sessions.selectPageWithMessages(USER_ID, 2L, 2)).thenReturn(List.of(
                summary(500L, null, "图片提问", 2, ChatRole.USER, "请帮我看看这张照片里的作物有什么问题", "chat/20261013/a.jpg")));

        PageVO<SessionItemVO> last = service.page(USER_ID, 2, 2);
        assertThat(last.hasMore()).isFalse();
        assertThat(last.list().get(0).lastMessage()).isEqualTo("[图片]请帮我看看这张照片里的作物有什么问题");

        // 超出总数：不查列表，返回空页
        PageVO<SessionItemVO> beyond = service.page(USER_ID, 3, 2);
        assertThat(beyond.list()).isEmpty();
        assertThat(beyond.total()).isEqualTo(3L);
        assertThat(beyond.hasMore()).isFalse();
        verify(sessions, never()).selectPageWithMessages(USER_ID, 4L, 2);

        when(sessions.countWithMessagesByUser(OTHER_USER)).thenReturn(0L);
        assertThat(service.page(OTHER_USER, 1, 20).list()).isEmpty();
        verify(sessions, never()).selectPageWithMessages(org.mockito.ArgumentMatchers.eq(OTHER_USER), anyLong(), anyInt());
    }

    @Test
    void lastMessageMissingGivesNullPreview() {
        SessionItemVO item = AiSessionService.toItem(summary(1L, null, " ", null, null, null, null));
        assertThat(item.lastMessage()).isNull();
        assertThat(item.title()).isEqualTo(AiSessionService.DEFAULT_TITLE);
        assertThat(item.messageCount()).isZero();
    }

    // ---------------------------------------------------------------- 详情

    @Test
    void detailReturnsRecordSummaryAndMessagesInOrderWithReasoning() {
        when(sessions.selectById(511L)).thenReturn(session(511L, USER_ID, 2036L, "追问：黄瓜霜霉病"));
        Recognition record = new Recognition();
        record.setId(2036L);
        record.setMode("PEST");
        record.setImageUrl("rec/20261013/r.jpg");
        record.setStatus(RecognitionStatus.SUCCESS);
        record.setTopName("黄瓜霜霉病");
        record.setConfidence(new BigDecimal("0.920"));
        record.setSeverity("中度");
        record.setCreatedAt(NOW.minusHours(1));
        when(recognitions.selectByIdAndUser(2036L, USER_ID)).thenReturn(record);
        AiMessage question = message(1L, ChatRole.USER, "这个病怎么处理？", "chat/20261013/q.jpg");
        AiMessage answer = message(2L, ChatRole.ASSISTANT, "处理建议……", null);
        answer.setReasoningContent("先看病斑");
        answer.setReasoningMs(12345);
        when(messages.selectBySession(511L)).thenReturn(List.of(question, answer));

        SessionDetailVO detail = service.detail(USER_ID, 511L);

        assertThat(detail.sessionId()).isEqualTo(511L);
        assertThat(detail.recordId()).isEqualTo(2036L);
        assertThat(detail.title()).isEqualTo("追问：黄瓜霜霉病");
        assertThat(detail.record().id()).isEqualTo(2036L);
        assertThat(detail.record().imageUrl()).isEqualTo(BASE + "/files/rec/20261013/r.jpg");
        assertThat(detail.record().confidence()).isEqualByComparingTo("0.92");
        assertThat(detail.messages()).hasSize(2);
        assertThat(detail.messages().get(0).role()).isEqualTo(ChatRole.USER);
        assertThat(detail.messages().get(0).imageUrl()).isEqualTo(BASE + "/files/chat/20261013/q.jpg");
        assertThat(detail.messages().get(0).reasoningContent()).isNull();
        assertThat(detail.messages().get(1).content()).isEqualTo("处理建议……");
        assertThat(detail.messages().get(1).imageUrl()).isNull();
        assertThat(detail.messages().get(1).reasoningContent()).isEqualTo("先看病斑");
        assertThat(detail.messages().get(1).reasoningMs()).isEqualTo(12345);
    }

    @Test
    void detailWithDeletedRecordHasNullRecordAndDefaultTitle() {
        when(sessions.selectById(600L)).thenReturn(session(600L, USER_ID, 2040L, null));
        when(recognitions.selectByIdAndUser(2040L, USER_ID)).thenReturn(null);
        when(messages.selectBySession(600L)).thenReturn(List.of());

        SessionDetailVO detail = service.detail(USER_ID, 600L);
        assertThat(detail.record()).isNull();
        assertThat(detail.recordId()).isEqualTo(2040L);
        assertThat(detail.title()).isEqualTo("新对话");
        assertThat(detail.messages()).isEmpty();

        when(sessions.selectById(601L)).thenReturn(session(601L, USER_ID, null, "普通对话"));
        service.detail(USER_ID, 601L);
        verify(recognitions, never()).selectByIdAndUser(org.mockito.ArgumentMatchers.isNull(), anyLong());
    }

    @Test
    void detailOfOthersOrMissingSessionIs40001() {
        when(sessions.selectById(700L)).thenReturn(session(700L, OTHER_USER, null, "别人的对话"));
        assertThatThrownBy(() -> service.detail(USER_ID, 700L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR);
        assertThatThrownBy(() -> service.detail(USER_ID, 701L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR);
        verify(messages, never()).selectBySession(anyLong());
    }

    // ---------------------------------------------------------------- 删除

    @Test
    void deleteIsIdempotentWhenSessionDoesNotExist() {
        service.delete(USER_ID, 800L);
        service.delete(USER_ID, 800L);
        verify(messages, never()).deleteBySession(anyLong());
        verify(sessions, never()).deleteByIdAndUser(anyLong(), anyLong());
    }

    @Test
    void deleteOthersSessionIs40001AndDeletesNothing() {
        when(sessions.selectById(801L)).thenReturn(session(801L, OTHER_USER, null, "别人的对话"));
        assertThatThrownBy(() -> service.delete(USER_ID, 801L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(40001);
        verify(messages, never()).selectImageUrlsBySession(anyLong());
        verify(messages, never()).deleteBySession(anyLong());
        verify(sessions, never()).deleteByIdAndUser(anyLong(), anyLong());
    }

    @Test
    void deleteRemovesMessagesSessionAndUnsharedChatImages() throws Exception {
        Path own = writeFile("chat/20261013/own.jpg");
        Path shared = writeFile("chat/20261013/shared.jpg");
        Path rec = writeFile("rec/20261013/r.jpg");
        when(sessions.selectById(900L)).thenReturn(session(900L, USER_ID, null, "带图对话"));
        when(messages.selectImageUrlsBySession(900L)).thenReturn(
                List.of("chat/20261013/own.jpg", "chat/20261013/shared.jpg", "rec/20261013/r.jpg"));
        when(messages.deleteBySession(900L)).thenReturn(4);
        when(messages.selectImageUrlsInUse(any())).thenReturn(List.of("chat/20261013/shared.jpg"));

        service.delete(USER_ID, 900L);

        InOrder order = inOrder(messages, sessions);
        order.verify(messages).selectImageUrlsBySession(900L);
        order.verify(messages).deleteBySession(900L);
        order.verify(sessions).deleteByIdAndUser(900L, USER_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> checked = ArgumentCaptor.forClass(Collection.class);
        order.verify(messages).selectImageUrlsInUse(checked.capture());
        // 只核对 chat/ 下的图片；识别照片不属于对话，不删除
        assertThat(checked.getValue()).containsExactly("chat/20261013/own.jpg", "chat/20261013/shared.jpg");

        assertThat(own).doesNotExist();
        assertThat(shared).exists();
        assertThat(rec).exists();
    }

    @Test
    void deleteWithoutImagesSkipsInUseCheck() {
        when(sessions.selectById(901L)).thenReturn(session(901L, USER_ID, null, "纯文字对话"));
        when(messages.selectImageUrlsBySession(901L)).thenReturn(List.of());
        service.delete(USER_ID, 901L);
        verify(messages).deleteBySession(901L);
        verify(sessions).deleteByIdAndUser(901L, USER_ID);
        verify(messages, never()).selectImageUrlsInUse(any());
    }

    @Test
    void imagesAreDeletedOnlyAfterCommit() throws Exception {
        Path image = writeFile("chat/20261013/later.jpg");
        when(sessions.selectById(902L)).thenReturn(session(902L, USER_ID, null, "带图对话"));
        when(messages.selectImageUrlsBySession(902L)).thenReturn(List.of("chat/20261013/later.jpg"));
        when(messages.selectImageUrlsInUse(any())).thenReturn(List.of());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.delete(USER_ID, 902L);
            assertThat(image).exists();
            List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
            assertThat(callbacks).hasSize(1);
            callbacks.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(image).doesNotExist();
    }

    // ---------------------------------------------------------------- 工具

    private Path writeFile(String relative) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1});
        return path;
    }

    private static AiSession session(Long id, Long userId, Long recordId, String title) {
        AiSession s = new AiSession();
        s.setId(id);
        s.setUserId(userId);
        s.setRecordId(recordId);
        s.setTitle(title);
        s.setCreatedAt(NOW);
        s.setUpdatedAt(NOW);
        return s;
    }

    private static AiMessage message(Long id, String role, String content, String image) {
        AiMessage m = new AiMessage(9L, role, content, image, NOW);
        m.setId(id);
        return m;
    }

    private static AiSessionSummary summary(Long id, Long recordId, String title, Integer count,
                                            String lastRole, String lastContent, String lastImage) {
        AiSessionSummary s = new AiSessionSummary();
        s.setId(id);
        s.setRecordId(recordId);
        s.setTitle(title);
        s.setCreatedAt(NOW.minusDays(1));
        s.setUpdatedAt(NOW);
        s.setMessageCount(count);
        s.setLastRole(lastRole);
        s.setLastContent(lastContent);
        s.setLastImageUrl(lastImage);
        return s;
    }
}
