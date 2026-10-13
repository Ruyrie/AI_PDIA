package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.heban.pestai.server.client.ChatMessage;
import com.heban.pestai.server.client.ChatPrompts;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ChatRole;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.config.AiSuggestionProperties;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiSession;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.vo.SuggestionVO;

/** 大家常问、限流、保存问题（重新生成）、上下文组装 */
class SuggestionAndChatSupportTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 14, 23, 0);

    private static AiSuggestionProperties suggestions() {
        AiSuggestionProperties p = new AiSuggestionProperties();
        p.setAutumn(List.of(
                new AiSuggestionProperties.Item(301L, "番茄叶片发黄卷曲是什么原因？", List.of("番茄")),
                new AiSuggestionProperties.Item(302L, "黄瓜叶子发黄卷边是什么原因？", List.of("黄瓜")),
                new AiSuggestionProperties.Item(303L, "小麦播种前怎样拌种防病？", List.of("小麦")),
                new AiSuggestionProperties.Item(304L, "白菜软腐病怎么预防？", List.of("白菜")),
                new AiSuggestionProperties.Item(305L, "秋季大棚白粉病怎么防治？", List.of())));
        p.validate();
        return p;
    }

    @Test
    void suggestionsPreferUserCropsAndAreStableWithinADay() {
        SuggestionService service = new SuggestionService(suggestions(), mock(UserMapper.class),
                Clock.system(ZONE));
        LocalDate day = LocalDate.of(2026, 10, 13);

        List<SuggestionVO> picked = service.pick(day, "番茄,黄瓜,小麦");
        assertThat(picked).extracting(SuggestionVO::id).containsExactlyInAnyOrder(301L, 302L, 303L);
        assertThat(service.pick(day, "番茄,黄瓜,小麦")).isEqualTo(picked);

        List<SuggestionVO> oneCrop = service.pick(day, "白菜");
        assertThat(oneCrop).hasSize(3);
        assertThat(oneCrop.get(0).id()).isEqualTo(304L);

        assertThat(service.pick(day, null)).hasSize(3).extracting(SuggestionVO::id).doesNotHaveDuplicates();
        assertThat(service.pick(LocalDate.of(2026, 1, 10), "番茄")).isEmpty();
    }

    @Test
    void duplicateSuggestionIdsFailAtStartup() {
        AiSuggestionProperties p = new AiSuggestionProperties();
        p.setSpring(List.of(new AiSuggestionProperties.Item(1L, "a", List.of())));
        p.setWinter(List.of(new AiSuggestionProperties.Item(1L, "b", List.of())));
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rateLimiterAllowsTenQuestionsPerSlidingMinute() {
        AtomicLong now = new AtomicLong(1_000_000);
        AiRateLimiter limiter = new AiRateLimiter(10, now::get);
        for (int i = 0; i < 10; i++) {
            limiter.acquire(1L);
            now.addAndGet(1_000);
        }
        assertThatThrownBy(() -> limiter.acquire(1L))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.TOO_FREQUENT);
        limiter.acquire(2L);
        now.addAndGet(51_000);
        limiter.acquire(1L);
    }

    @Test
    void regenerateDeletesLastAnswerAndReusesQuestion() {
        AiMessageMapper messages = mock(AiMessageMapper.class);
        AiSessionMapper sessions = mock(AiSessionMapper.class);
        AiMessageTxService tx = new AiMessageTxService(messages, sessions, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        AiSession session = session(null, "已有标题");

        when(messages.selectTail(9L, 2)).thenReturn(List.of(
                message(31L, ChatRole.ASSISTANT, "半截回答", null),
                message(30L, ChatRole.USER, "这个病怎么处理？", "chat/20261013/a.jpg")));
        long id = tx.saveQuestion(session, "这个病怎么处理？", "chat/20261013/a.jpg", true);
        assertThat(id).isEqualTo(30L);
        verify(messages).deleteById(31L);
        verify(messages, never()).insert(any());
    }

    @Test
    void regenerateOfUnknownQuestionInsertsWithoutDeleting() {
        AiMessageMapper messages = mock(AiMessageMapper.class);
        AiMessageTxService tx = new AiMessageTxService(messages, mock(AiSessionMapper.class),
                Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        when(messages.selectTail(9L, 2)).thenReturn(List.of(
                message(31L, ChatRole.ASSISTANT, "上一轮回答", null),
                message(30L, ChatRole.USER, "上一个问题", null)));
        when(messages.insert(any())).thenAnswer(inv -> {
            ((AiMessage) inv.getArgument(0)).setId(32L);
            return 1;
        });
        assertThat(tx.saveQuestion(session(null, "t"), "新的问题", null, true)).isEqualTo(32L);
        verify(messages, never()).deleteById(any());
    }

    @Test
    void firstQuestionOfPlainSessionBecomesTitle() {
        AiMessageMapper messages = mock(AiMessageMapper.class);
        when(messages.insert(any())).thenAnswer(inv -> {
            ((AiMessage) inv.getArgument(0)).setId(40L);
            return 1;
        });
        AiSessionMapper sessions = mock(AiSessionMapper.class);
        AiMessageTxService tx = new AiMessageTxService(messages, sessions, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        AiSession plain = session(null, null);
        tx.saveQuestion(plain, "番茄叶片发黄卷曲是什么原因？\n下部老叶先黄，然后慢慢往上蔓延到顶部新叶，该怎么办", null, false);
        // 前 30 个字（换行合并为一个空格）；补写标题的 UPDATE 同时更新 updated_at，不再单独 touch
        verify(sessions).updateTitle(9L, "番茄叶片发黄卷曲是什么原因？ 下部老叶先黄，然后慢慢往上蔓延", NOW);
        verify(sessions, never()).touch(any(), any());
        assertThat(plain.getTitle()).isEqualTo("番茄叶片发黄卷曲是什么原因？ 下部老叶先黄，然后慢慢往上蔓延");
        assertThat(plain.getUpdatedAt()).isEqualTo(NOW);

        // 第二条提问：标题已有，不再改标题，只更新 updated_at
        tx.saveQuestion(plain, "第二个问题", null, false);
        verify(sessions).touch(9L, NOW);

        AiSessionMapper sessions2 = mock(AiSessionMapper.class);
        new AiMessageTxService(messages, sessions2, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE))
                .saveQuestion(session(2036L, "追问：黄瓜霜霉病"), "怎么处理", null, false);
        verify(sessions2, never()).updateTitle(any(), any(), any());
        verify(sessions2).touch(9L, NOW);
    }

    @Test
    void imageQuestionWithoutTextIsTitledImageQuestion() {
        assertThat(AiMessageTxService.titleOf(AiMessageTxService.APP_DEFAULT_IMAGE_QUESTION, "chat/20261013/a.jpg"))
                .isEqualTo(AiMessageTxService.IMAGE_TITLE);
        assertThat(AiMessageTxService.titleOf("  ", "chat/20261013/a.jpg")).isEqualTo("图片提问");
        assertThat(AiMessageTxService.titleOf("叶子背面的黑点是什么", "chat/20261013/a.jpg")).isEqualTo("叶子背面的黑点是什么");
        // 没有图片时，默认图片提问的文字按普通问题处理
        assertThat(AiMessageTxService.titleOf(AiMessageTxService.APP_DEFAULT_IMAGE_QUESTION, null))
                .isEqualTo(AiMessageTxService.APP_DEFAULT_IMAGE_QUESTION);
        assertThat(AiMessageTxService.titleOf("😀".repeat(40), null)).isEqualTo("😀".repeat(30));

        AiMessageMapper messages = mock(AiMessageMapper.class);
        when(messages.insert(any())).thenAnswer(inv -> {
            ((AiMessage) inv.getArgument(0)).setId(41L);
            return 1;
        });
        AiSessionMapper sessions = mock(AiSessionMapper.class);
        new AiMessageTxService(messages, sessions, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE))
                .saveQuestion(session(null, " "), AiMessageTxService.APP_DEFAULT_IMAGE_QUESTION, "chat/20261013/a.jpg", false);
        verify(sessions).updateTitle(9L, "图片提问", NOW);
    }

    @Test
    void regenerateReusingQuestionDoesNotTouchSession() {
        AiMessageMapper messages = mock(AiMessageMapper.class);
        AiSessionMapper sessions = mock(AiSessionMapper.class);
        when(messages.selectTail(9L, 2)).thenReturn(List.of(message(30L, ChatRole.USER, "这个病怎么处理？", null)));
        new AiMessageTxService(messages, sessions, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE))
                .saveQuestion(session(null, "已有标题"), "这个病怎么处理？", null, true);
        verify(messages, never()).insert(any());
        verify(sessions, never()).touch(any(), any());
    }

    @Test
    void promptContainsConclusionHistoryAndCurrentQuestion() {
        Recognition r = new Recognition();
        r.setStatus(RecognitionStatus.SUCCESS);
        r.setTopName("黄瓜霜霉病");
        r.setConfidence(new BigDecimal("0.920"));
        r.setSeverity("中度");
        r.setCategory("卵菌病害");
        r.setPart("叶部");
        r.setSpreadFast(true);
        r.setUrgencyDays(3);
        r.setEvidence("叶背有灰黑色霉层");

        List<AiMessage> history = List.of(
                message(1L, ChatRole.USER, "第一问", "chat/x.jpg"),
                message(2L, ChatRole.ASSISTANT, "第一答", null),
                message(3L, ChatRole.USER, "没有回答的问题", null));
        List<ChatMessage> messages = PromptBuilder.assemble(r, history, "这个病怎么处理？", "data:image/jpeg;base64,AA");

        assertThat(messages.get(0).role()).isEqualTo(ChatRole.SYSTEM);
        assertThat(messages.get(0).text())
                .contains(ChatPrompts.CONCLUSION_MARK + "黄瓜霜霉病，匹配度 92%，中度，卵菌病害，叶部，传播快，建议 3 天内处理")
                .contains("识别依据：叶背有灰黑色霉层")
                .contains("编号列表");
        assertThat(messages.get(1).text()).isEqualTo("第一问" + ChatPrompts.IMAGE_NOTE);
        assertThat(messages.get(2).role()).isEqualTo(ChatRole.ASSISTANT);
        ChatMessage last = messages.get(messages.size() - 1);
        assertThat(last.role()).isEqualTo(ChatRole.USER);
        assertThat(last.text()).isEqualTo("没有回答的问题\n这个病怎么处理？");
        assertThat(last.imageDataUrl()).isEqualTo("data:image/jpeg;base64,AA");
        assertThat(messages).hasSize(4);
    }

    @Test
    void deletedOrFailedRecordAddsNoConclusion() {
        Recognition failed = new Recognition();
        failed.setStatus(RecognitionStatus.FAILED);
        assertThat(PromptBuilder.conclusionText(failed)).isNull();
        assertThat(PromptBuilder.conclusionText(null)).isNull();
        assertThat(PromptBuilder.systemPrompt(null)).doesNotContain(ChatPrompts.CONCLUSION_MARK);
    }

    @Test
    void cropsAreNormalized() {
        assertThat(UserService.normalizeCrops(" 番茄, 黄瓜,,番茄，小麦、 ")).isEqualTo("番茄,黄瓜,小麦");
        assertThat(UserService.normalizeCrops(" , ")).isNull();
        assertThat(UserService.normalizeCrops(null)).isNull();
    }

    private static AiSession session(Long recordId, String title) {
        AiSession s = new AiSession();
        s.setId(9L);
        s.setUserId(1L);
        s.setRecordId(recordId);
        s.setTitle(title);
        return s;
    }

    private static AiMessage message(Long id, String role, String content, String image) {
        AiMessage m = new AiMessage(9L, role, content, image, NOW);
        m.setId(id);
        return m;
    }
}
