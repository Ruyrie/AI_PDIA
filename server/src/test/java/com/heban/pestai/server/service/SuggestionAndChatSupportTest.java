package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.heban.pestai.server.client.ChatMessage;
import com.heban.pestai.server.client.ChatPrompts;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ChatRole;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.config.AiSuggestionProperties;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiMessageImage;
import com.heban.pestai.server.entity.AiSession;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.vo.SuggestionVO;

/** 大家常问、限流、保存问题（多图、重新生成）、上下文组装 */
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
    void suggestionsPickedByCropCarryTheMatchedCrop() {
        SuggestionService service = new SuggestionService(suggestions(), mock(UserMapper.class),
                Clock.system(ZONE));
        LocalDate day = LocalDate.of(2026, 10, 13);

        List<SuggestionVO> picked = service.pick(day, "番茄,黄瓜,小麦");
        assertThat(picked).allSatisfy(s -> assertThat(s.crop()).isNotNull());
        assertThat(picked).filteredOn(s -> s.id() == 301L).extracting(SuggestionVO::crop).containsExactly("番茄");
        assertThat(picked).filteredOn(s -> s.id() == 302L).extracting(SuggestionVO::crop).containsExactly("黄瓜");
        assertThat(picked).filteredOn(s -> s.id() == 303L).extracting(SuggestionVO::crop).containsExactly("小麦");

        // 按作物挑中的排在前面；其余（通用问题、其他作物的问题）crop 为 null
        List<SuggestionVO> oneCrop = service.pick(day, " 白菜 ");
        assertThat(oneCrop.get(0).id()).isEqualTo(304L);
        assertThat(oneCrop.get(0).crop()).isEqualTo("白菜");
        assertThat(oneCrop.subList(1, 3)).extracting(SuggestionVO::crop).containsOnlyNulls();

        // 没有填写作物 / 作物都不在问题池中：全部为 null
        assertThat(service.pick(day, null)).extracting(SuggestionVO::crop).containsOnlyNulls();
        assertThat(service.pick(day, "")).extracting(SuggestionVO::crop).containsOnlyNulls();
        assertThat(service.pick(day, "水稻")).extracting(SuggestionVO::crop).containsOnlyNulls();

        // 问题适用多种作物时，取用户作物中（按填写顺序）第一个属于它的
        AiSuggestionProperties.Item both = new AiSuggestionProperties.Item(1L, "瓜果", List.of("黄瓜", "番茄"));
        assertThat(SuggestionService.matchedCrop(both, List.of("番茄", "黄瓜"))).isEqualTo("番茄");
        assertThat(SuggestionService.matchedCrop(both, List.of("小麦", "黄瓜"))).isEqualTo("黄瓜");
        assertThat(SuggestionService.matchedCrop(both, List.of())).isNull();
        assertThat(SuggestionService.matchedCrop(new AiSuggestionProperties.Item(2L, "通用", List.of()),
                List.of("番茄"))).isNull();
    }

    @Test
    void userWithoutCropsGetsSuggestionsWithoutCrop() {
        UserMapper users = mock(UserMapper.class);
        when(users.selectCropsById(7L)).thenReturn("番茄");
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        SuggestionService service = new SuggestionService(suggestions(), users, clock);

        assertThat(service.listForUser(7L).get(0)).isEqualTo(
                new SuggestionVO(301L, "番茄叶片发黄卷曲是什么原因？", "番茄"));
        // 用户不存在或没有填写作物（selectCropsById 为 null）
        assertThat(service.listForUser(8L)).hasSize(3).extracting(SuggestionVO::crop).containsOnlyNulls();
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
        when(messages.selectImagesByMessageIds(List.of(30L))).thenReturn(List.of(
                image(30L, 0, "chat/20261013/a.jpg"), image(30L, 1, "chat/20261013/b.jpg")));
        long id = tx.saveQuestion(session, "这个病怎么处理？",
                List.of("chat/20261013/a.jpg", "chat/20261013/b.jpg"), true);
        assertThat(id).isEqualTo(30L);
        verify(messages).deleteById(31L);
        verify(messages, never()).insert(any());
        verify(messages, never()).insertImages(any(), anyList(), any());
    }

    @Test
    void regenerateComparesImageListInOrderWithLegacyFallback() {
        AiMessage legacy = message(30L, ChatRole.USER, "这个病怎么处理？", "chat/20261013/a.jpg");
        // 迁移前的旧消息没有图片行：按 [image_url] 比较
        assertThat(AiMessageTxService.sameQuestion(legacy, "这个病怎么处理？", List.of("chat/20261013/a.jpg"))).isTrue();
        assertThat(AiMessageTxService.sameQuestion(legacy, "这个病怎么处理？", List.of())).isFalse();
        assertThat(AiMessageTxService.sameQuestion(legacy, "别的问题", List.of("chat/20261013/a.jpg"))).isFalse();

        AiMessage multi = message(31L, ChatRole.USER, "", "chat/20261013/a.jpg");
        multi.setImageUrls(List.of("chat/20261013/a.jpg", "chat/20261013/b.jpg"));
        assertThat(AiMessageTxService.sameQuestion(multi, "", List.of("chat/20261013/a.jpg", "chat/20261013/b.jpg")))
                .isTrue();
        // 顺序不同、少一张都不是同一个问题
        assertThat(AiMessageTxService.sameQuestion(multi, "", List.of("chat/20261013/b.jpg", "chat/20261013/a.jpg")))
                .isFalse();
        assertThat(AiMessageTxService.sameQuestion(multi, "", List.of("chat/20261013/a.jpg"))).isFalse();

        AiMessage text = message(32L, ChatRole.USER, "纯文字", null);
        assertThat(AiMessageTxService.sameQuestion(text, "纯文字", null)).isTrue();
        assertThat(AiMessageTxService.sameQuestion(text, "纯文字", List.of())).isTrue();
        assertThat(AiMessageTxService.sameQuestion(message(33L, ChatRole.ASSISTANT, "纯文字", null), "纯文字", null))
                .isFalse();

        // 重新生成时图片顺序变了：按普通提问插入，不删除上一条回答
        AiMessageMapper messages = mock(AiMessageMapper.class);
        when(messages.selectTail(9L, 2)).thenReturn(List.of(
                message(41L, ChatRole.ASSISTANT, "上一轮回答", null),
                message(40L, ChatRole.USER, "", "chat/20261013/a.jpg")));
        when(messages.selectImagesByMessageIds(List.of(40L))).thenReturn(List.of(
                image(40L, 0, "chat/20261013/a.jpg"), image(40L, 1, "chat/20261013/b.jpg")));
        when(messages.insert(any())).thenAnswer(inv -> {
            ((AiMessage) inv.getArgument(0)).setId(42L);
            return 1;
        });
        AiMessageTxService tx = new AiMessageTxService(messages, mock(AiSessionMapper.class),
                Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        assertThat(tx.saveQuestion(session(null, "t"), "", List.of("chat/20261013/b.jpg", "chat/20261013/a.jpg"), true))
                .isEqualTo(42L);
        verify(messages, never()).deleteById(any());
        verify(messages).insertImages(42L, List.of("chat/20261013/b.jpg", "chat/20261013/a.jpg"), NOW);
    }

    @Test
    void imageQuestionStoresFirstImageOnMessageAndAllImagesInOrder() {
        AiMessageMapper messages = mock(AiMessageMapper.class);
        when(messages.insert(any())).thenAnswer(inv -> {
            ((AiMessage) inv.getArgument(0)).setId(50L);
            return 1;
        });
        AiSessionMapper sessions = mock(AiSessionMapper.class);
        List<String> images = List.of("chat/20261014/c.jpg", "chat/20261014/a.png", "chat/20261014/b.jpg");
        long id = new AiMessageTxService(messages, sessions, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE))
                .saveQuestion(session(null, null), "", images, false);

        assertThat(id).isEqualTo(50L);
        ArgumentCaptor<AiMessage> saved = ArgumentCaptor.forClass(AiMessage.class);
        InOrder order = inOrder(messages, sessions);
        order.verify(messages).insert(saved.capture());
        order.verify(messages).insertImages(50L, images, NOW);
        order.verify(sessions).updateTitle(9L, "图片提问", NOW);
        // 只发图片：content 为空字符串，image_url 为第一张
        assertThat(saved.getValue().getContent()).isEmpty();
        assertThat(saved.getValue().getRole()).isEqualTo(ChatRole.USER);
        assertThat(saved.getValue().getImageUrl()).isEqualTo("chat/20261014/c.jpg");

        // 没有图片：不写图片行
        AiMessageMapper textOnly = mock(AiMessageMapper.class);
        when(textOnly.insert(any())).thenAnswer(inv -> {
            ((AiMessage) inv.getArgument(0)).setId(51L);
            return 1;
        });
        new AiMessageTxService(textOnly, mock(AiSessionMapper.class), Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE))
                .saveQuestion(session(null, "t"), "问题", List.of(), false);
        verify(textOnly, never()).insertImages(any(), anyList(), any());
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
        assertThat(tx.saveQuestion(session(null, "t"), "新的问题", List.of(), true)).isEqualTo(32L);
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
        List<String> one = List.of("chat/20261013/a.jpg");
        List<String> three = List.of("chat/20261013/a.jpg", "chat/20261013/b.jpg", "chat/20261013/c.jpg");
        assertThat(AiMessageTxService.titleOf(AiMessageTxService.APP_DEFAULT_IMAGE_QUESTION, one))
                .isEqualTo(AiMessageTxService.IMAGE_TITLE);
        assertThat(AiMessageTxService.titleOf(AiMessageTxService.APP_DEFAULT_IMAGE_QUESTION, three)).isEqualTo("图片提问");
        assertThat(AiMessageTxService.titleOf("  ", one)).isEqualTo("图片提问");
        assertThat(AiMessageTxService.titleOf("", three)).isEqualTo("图片提问");
        assertThat(AiMessageTxService.titleOf("叶子背面的黑点是什么", three)).isEqualTo("叶子背面的黑点是什么");
        // 没有图片时，默认图片提问的文字按普通问题处理
        assertThat(AiMessageTxService.titleOf(AiMessageTxService.APP_DEFAULT_IMAGE_QUESTION, null))
                .isEqualTo(AiMessageTxService.APP_DEFAULT_IMAGE_QUESTION);
        assertThat(AiMessageTxService.titleOf(AiMessageTxService.APP_DEFAULT_IMAGE_QUESTION, List.of()))
                .isEqualTo(AiMessageTxService.APP_DEFAULT_IMAGE_QUESTION);
        assertThat(AiMessageTxService.titleOf("", List.of())).isNull();
        assertThat(AiMessageTxService.titleOf("😀".repeat(40), null)).isEqualTo("😀".repeat(30));

        AiMessageMapper messages = mock(AiMessageMapper.class);
        when(messages.insert(any())).thenAnswer(inv -> {
            ((AiMessage) inv.getArgument(0)).setId(41L);
            return 1;
        });
        AiSessionMapper sessions = mock(AiSessionMapper.class);
        new AiMessageTxService(messages, sessions, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE))
                .saveQuestion(session(null, " "), AiMessageTxService.APP_DEFAULT_IMAGE_QUESTION, List.of("chat/20261013/a.jpg"),
                        false);
        verify(sessions).updateTitle(9L, "图片提问", NOW);
    }

    @Test
    void regenerateReusingQuestionDoesNotTouchSession() {
        AiMessageMapper messages = mock(AiMessageMapper.class);
        AiSessionMapper sessions = mock(AiSessionMapper.class);
        when(messages.selectTail(9L, 2)).thenReturn(List.of(message(30L, ChatRole.USER, "这个病怎么处理？", null)));
        new AiMessageTxService(messages, sessions, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE))
                .saveQuestion(session(null, "已有标题"), "这个病怎么处理？", null, true);
        // 末尾的问题不带图：不查图片行
        verify(messages, never()).selectImagesByMessageIds(any());
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
        List<ChatMessage> messages = PromptBuilder.assemble(UserProfile.EMPTY, r, history, "这个病怎么处理？",
                List.of("data:image/jpeg;base64,AA"));

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
        assertThat(last.imageDataUrls()).containsExactly("data:image/jpeg;base64,AA");
        assertThat(messages).hasSize(4);
    }

    @Test
    void historyNotesCountImagesAndImageOnlyQuestionsAreKept() {
        AiMessage two = message(1L, ChatRole.USER, "这两张哪张更严重", "chat/a.jpg");
        two.setImageUrls(List.of("chat/a.jpg", "chat/b.jpg"));
        AiMessage onlyThree = message(3L, ChatRole.USER, "", "chat/c.jpg");
        onlyThree.setImageUrls(List.of("chat/c.jpg", "chat/d.jpg", "chat/e.jpg"));
        List<AiMessage> history = List.of(
                two,
                message(2L, ChatRole.ASSISTANT, "第二张更严重", null),
                onlyThree,
                message(4L, ChatRole.ASSISTANT, "都是霜霉病", null),
                // 只发了 1 张图片（新版 App，content 为空）
                message(5L, ChatRole.USER, " ", "chat/f.jpg"),
                // 只有思考、正文为空的回答：跳过
                message(6L, ChatRole.ASSISTANT, "", null),
                // 旧版 App 的默认图片提问：保持原样
                message(7L, ChatRole.USER, AiMessageTxService.APP_DEFAULT_IMAGE_QUESTION, "chat/g.jpg"),
                message(8L, ChatRole.ASSISTANT, "叶片缺镁", null));

        List<ChatMessage> messages = PromptBuilder.assemble(UserProfile.EMPTY, null, history, "",
                List.of("data:image/jpeg;base64,AA", "data:image/png;base64,BB"));

        assertThat(messages).extracting(ChatMessage::text).containsExactly(
                messages.get(0).text(),
                "这两张哪张更严重（用户附带了 2 张图片）",
                "第二张更严重",
                "（用户发送了 3 张图片）",
                "都是霜霉病",
                "（用户发送了一张图片）\n" + AiMessageTxService.APP_DEFAULT_IMAGE_QUESTION + ChatPrompts.IMAGE_NOTE,
                "叶片缺镁",
                ChatPrompts.IMAGES_QUESTION);
        // 历史中的图片不再发给模型，只有当前问题带图（全部图片，按顺序）
        assertThat(messages.subList(0, messages.size() - 1)).allMatch(m -> m.imageDataUrls().isEmpty());
        assertThat(messages.get(messages.size() - 1).imageDataUrls())
                .containsExactly("data:image/jpeg;base64,AA", "data:image/png;base64,BB");

        // 只有 1 张图片且没有文字：沿用单图的默认问题
        List<ChatMessage> single = PromptBuilder.assemble(UserProfile.EMPTY, null, List.of(), " ",
                List.of("data:image/jpeg;base64,AA"));
        assertThat(single.get(1).text()).isEqualTo(ChatPrompts.IMAGE_QUESTION).isEqualTo("请帮我看看这张照片里的作物有什么问题");
        assertThat(ChatPrompts.IMAGES_QUESTION).isEqualTo("请帮我看看这些照片里的作物有什么问题");
        // 有文字时用文字本身
        assertThat(PromptBuilder.assemble(UserProfile.EMPTY, null, List.of(), "叶子发黄",
                List.of("data:image/jpeg;base64,AA")).get(1).text()).isEqualTo("叶子发黄");
        assertThat(PromptBuilder.assemble(UserProfile.EMPTY, null, List.of(), "纯文字", null).get(1).hasImage()).isFalse();
    }

    @Test
    void consecutiveUserMessagesMergeTextAndImagesInOrder() {
        AiMessage unanswered = message(1L, ChatRole.USER, "上一个没回答的问题", "chat/a.jpg");
        List<ChatMessage> messages = PromptBuilder.assemble(UserProfile.EMPTY, null, List.of(unanswered), "这个呢",
                List.of("data:image/jpeg;base64,AA", "data:image/jpeg;base64,BB"));
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).text()).isEqualTo("上一个没回答的问题" + ChatPrompts.IMAGE_NOTE + "\n这个呢");
        assertThat(messages.get(1).imageDataUrls()).containsExactly("data:image/jpeg;base64,AA", "data:image/jpeg;base64,BB");
    }

    @Test
    void historyImagesAreLoadedInOneQueryOnlyForMessagesWithImages() {
        AiMessageMapper mapper = mock(AiMessageMapper.class);
        AiMessage withImages = message(10L, ChatRole.USER, "", "chat/a.jpg");
        AiMessage legacy = message(12L, ChatRole.USER, "旧的带图提问", "chat/old.jpg");
        AiMessage text = message(11L, ChatRole.ASSISTANT, "回答", null);
        when(mapper.selectImagesByMessageIds(List.of(10L, 12L))).thenReturn(List.of(
                image(10L, 0, "chat/a.jpg"), image(10L, 1, "chat/b.jpg")));

        ChatImages.attach(mapper, List.of(withImages, text, legacy));

        assertThat(ChatImages.of(withImages)).containsExactly("chat/a.jpg", "chat/b.jpg");
        // 没有图片行的旧消息退回 [image_url]
        assertThat(ChatImages.of(legacy)).containsExactly("chat/old.jpg");
        assertThat(ChatImages.of(text)).isEmpty();

        AiMessageMapper untouched = mock(AiMessageMapper.class);
        ChatImages.attach(untouched, List.of(text));
        verify(untouched, never()).selectImagesByMessageIds(any());
    }

    @Test
    void deletedOrFailedRecordAddsNoConclusion() {
        Recognition failed = new Recognition();
        failed.setStatus(RecognitionStatus.FAILED);
        assertThat(PromptBuilder.conclusionText(failed)).isNull();
        assertThat(PromptBuilder.conclusionText(null)).isNull();
        assertThat(PromptBuilder.systemPrompt(UserProfile.EMPTY, null)).doesNotContain(ChatPrompts.CONCLUSION_MARK);
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

    private static AiMessageImage image(Long messageId, int sortNo, String path) {
        AiMessageImage i = new AiMessageImage();
        i.setMessageId(messageId);
        i.setSortNo(sortNo);
        i.setImageUrl(path);
        return i;
    }
}
