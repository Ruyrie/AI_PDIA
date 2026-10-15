package com.heban.pestai.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.heban.pestai.server.common.Mode;
import com.heban.pestai.server.config.PestaiProperties;
import com.heban.pestai.server.service.ParsedVision;
import com.heban.pestai.server.service.VisionResultParser;
import com.heban.pestai.server.vo.CandidateVO;

class MockClientsTest {

    private static PestaiProperties.Mock mock(int imageBadPercent) {
        PestaiProperties.Mock mock = new PestaiProperties.Mock();
        mock.setVisionDelayMs(0);
        mock.setChatChunkDelayMs(0);
        mock.setImageBadPercent(imageBadPercent);
        return mock;
    }

    @Test
    void everyMockSamplePassesTheRealParser() throws Exception {
        List<CandidateVO> others = new ArrayList<>();
        for (String sample : MockVisionClient.PEST_SAMPLES) {
            ParsedVision p = VisionResultParser.parse(sample, Mode.PEST);
            assertThat(p.isSuccess()).isTrue();
            assertThat(p.severity()).isNotNull();
            others.addAll(p.others());
        }
        for (String sample : MockVisionClient.CROP_SAMPLES) {
            ParsedVision p = VisionResultParser.parse(sample, Mode.CROP);
            assertThat(p.isSuccess()).isTrue();
            assertThat(p.severity()).isNull();
            others.addAll(p.others());
        }
        assertThat(VisionResultParser.parse(MockVisionClient.IMAGE_BAD, Mode.PEST).isSuccess()).isFalse();

        // 「其他可能」与提示词要求一致：都有 reason、difference，且各不超过 40 字
        assertThat(others).hasSizeGreaterThan(5);
        for (CandidateVO c : others) {
            assertThat(c.reason()).as(c.name()).isNotBlank();
            assertThat(c.difference()).as(c.name()).isNotBlank();
            assertThat(c.reason().codePointCount(0, c.reason().length())).as(c.name()).isLessThanOrEqualTo(40);
            assertThat(c.difference().codePointCount(0, c.difference().length())).as(c.name()).isLessThanOrEqualTo(40);
        }
    }

    @Test
    void visionInstructionAddsCropHintOnlyWhenUserHasCrops() {
        assertThat(VisionPrompts.userInstruction(null)).isEqualTo(VisionPrompts.USER_INSTRUCTION);
        assertThat(VisionPrompts.userInstruction(List.of())).isEqualTo(VisionPrompts.USER_INSTRUCTION);

        String withCrops = VisionPrompts.userInstruction(List.of("番茄", "黄瓜", "小麦"));
        assertThat(withCrops).isEqualTo("请识别这张照片，严格按要求只返回一个 JSON 对象。\n"
                + "参考信息：该用户主要种植番茄、黄瓜、小麦。仅当照片中的作物种类难以判断时参考；"
                + "照片能明确看出作物时以照片为准，不要因为这条信息改变判断。");
        // 只多一行：第一行仍是要求只返回 JSON 的指令
        assertThat(withCrops.lines().toList()).hasSize(2).first().isEqualTo(VisionPrompts.USER_INSTRUCTION);
        // 系统提示（JSON 输出约定）与作物无关，PEST、CROP 各自不变
        assertThat(VisionPrompts.systemPrompt(Mode.PEST)).doesNotContain("参考信息");
        assertThat(VisionPrompts.systemPrompt(Mode.CROP)).doesNotContain("参考信息");
    }

    @Test
    void promptExamplesIncludeReasonAndDifferenceForOthers() throws Exception {
        for (String mode : List.of(Mode.PEST, Mode.CROP)) {
            String prompt = VisionPrompts.systemPrompt(mode);
            assertThat(prompt).contains("reason（为什么可能是它").contains("difference（与 top_name 的区别");
            // 提示词中的 JSON 示例本身也能通过解析，且每条「其他可能」都带说明
            String example = prompt.lines().filter(l -> l.startsWith("{\"status\":\"SUCCESS\"")).findFirst().orElseThrow();
            ParsedVision p = VisionResultParser.parse(example, mode);
            assertThat(p.others()).isNotEmpty().allSatisfy(c -> {
                assertThat(c.reason()).isNotBlank();
                assertThat(c.difference()).isNotBlank();
            });
        }
    }

    @Test
    void sameImageAlwaysGivesSameResult() throws Exception {
        MockVisionClient client = new MockVisionClient(mock(0));
        byte[] image = "pretend-jpeg-bytes".getBytes(StandardCharsets.UTF_8);
        String first = client.recognize(image, "image/jpeg", Mode.PEST, List.of(), Duration.ofSeconds(25));
        String second = client.recognize(image, "image/jpeg", Mode.PEST, List.of(), Duration.ofSeconds(25));
        assertThat(first).isEqualTo(second);
        // Mock 不使用作物参考信息
        assertThat(client.recognize(image, "image/jpeg", Mode.PEST, List.of("小麦"), Duration.ofSeconds(25)))
                .isEqualTo(first);
        assertThat(MockVisionClient.PEST_SAMPLES).contains(first);
        assertThat(client.isMock()).isTrue();
    }

    @Test
    void differentImagesRotateThroughSamples() throws Exception {
        MockVisionClient client = new MockVisionClient(mock(0));
        List<String> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            results.add(client.recognize(("img-" + i).getBytes(StandardCharsets.UTF_8), "image/jpeg", Mode.CROP, List.of(),
                    Duration.ofSeconds(25)));
        }
        assertThat(results.stream().distinct().count()).isGreaterThan(1);
        assertThat(MockVisionClient.CROP_SAMPLES).containsAll(results);
    }

    @Test
    void imageBadPercentControlsSimulatedBlurryPhotos() throws Exception {
        MockVisionClient alwaysBad = new MockVisionClient(mock(100));
        assertThat(alwaysBad.recognize(new byte[]{1, 2, 3}, "image/jpeg", Mode.PEST, List.of(), Duration.ofSeconds(25)))
                .isEqualTo(MockVisionClient.IMAGE_BAD);
    }

    @Test
    void delayLongerThanBudgetSimulatesTimeout() {
        PestaiProperties.Mock slow = mock(0);
        slow.setVisionDelayMs(5_000);
        MockVisionClient client = new MockVisionClient(slow);
        assertThatThrownBy(() -> client.recognize(new byte[]{1}, "image/jpeg", Mode.PEST, List.of(), Duration.ofMillis(10)))
                .isInstanceOf(VisionException.class)
                .extracting(e -> ((VisionException) e).getReason())
                .isEqualTo(VisionException.Reason.TIMEOUT);
    }

    @Test
    void mockChatStreamsChunksThatRebuildTheAnswer() throws Exception {
        MockChatClient client = new MockChatClient(mock(0));
        List<ChatMessage> messages = List.of(
                ChatMessage.system(ChatPrompts.ROLE + "\n" + ChatPrompts.CONCLUSION_MARK + "黄瓜霜霉病，匹配度 92%，中度"),
                ChatMessage.user("这个病怎么处理？需要马上打药吗？"));
        List<String> chunks = new ArrayList<>();
        client.stream(messages, chunks::add, new StreamControl());

        String answer = String.join("", chunks);
        assertThat(chunks.size()).isGreaterThan(5);
        assertThat(answer).contains("**黄瓜霜霉病**").contains("处理建议").contains("1. ").contains("烯酰吗啉");
        assertThat(answer).isEqualTo(MockAnswers.answer(messages));
    }

    @Test
    void mockChatEmitsReasoningBeforeAnswerButVisionMockDoesNot() throws Exception {
        List<ChatMessage> messages = List.of(
                ChatMessage.system(ChatPrompts.ROLE + "\n" + ChatPrompts.CONCLUSION_MARK + "黄瓜霜霉病，匹配度 92%，中度"),
                ChatMessage.user("这个病怎么处理？"));
        List<String> events = new ArrayList<>();
        StreamCallback recorder = new StreamCallback() {
            @Override
            public void onDelta(String delta) {
                events.add("C" + delta);
            }

            @Override
            public void onReasoning(String delta) {
                events.add("R" + delta);
            }
        };

        new MockChatClient(mock(0)).stream(messages, recorder, new StreamControl());
        int firstContent = events.indexOf(events.stream().filter(e -> e.startsWith("C")).findFirst().orElseThrow());
        assertThat(firstContent).isGreaterThan(1);
        assertThat(events.subList(0, firstContent)).allMatch(e -> e.startsWith("R"));
        assertThat(events.subList(firstContent, events.size())).allMatch(e -> e.startsWith("C"));
        String reasoning = events.subList(0, firstContent).stream().map(e -> e.substring(1)).reduce("", String::concat);
        assertThat(reasoning).isEqualTo(MockAnswers.reasoning(messages)).contains("黄瓜霜霉病").contains("\n");

        events.clear();
        new MockVisionClient(mock(0)).streamChat(messages, recorder, new StreamControl());
        assertThat(events).isNotEmpty().allMatch(e -> e.startsWith("C"));
    }

    @Test
    void mockChatStopsDuringReasoningWithoutAnswer() throws Exception {
        StreamControl control = new StreamControl();
        List<String> answer = new ArrayList<>();
        int[] reasoning = {0};
        new MockChatClient(mock(0)).stream(List.of(ChatMessage.user("蚜虫怎么防治")), new StreamCallback() {
            @Override
            public void onDelta(String delta) {
                answer.add(delta);
            }

            @Override
            public void onReasoning(String delta) {
                if (++reasoning[0] == 2) {
                    control.cancel();
                }
            }
        }, control);
        assertThat(reasoning[0]).isEqualTo(2);
        assertThat(answer).isEmpty();
    }

    @Test
    void mockChatAnswersGenericQuestionAndMentionsPhoto() throws Exception {
        MockChatClient client = new MockChatClient(mock(0));
        List<ChatMessage> messages = List.of(
                ChatMessage.system(ChatPrompts.ROLE),
                ChatMessage.user("请帮我看看这张照片里的作物有什么问题").withImage("data:image/jpeg;base64,AAAA"));
        StringBuilder sb = new StringBuilder();
        client.stream(messages, sb::append, new StreamControl());
        assertThat(sb.toString()).startsWith("从照片看，").contains("初步建议");
    }

    @Test
    void mockChatStopsWhenCancelledOrClientGone() throws Exception {
        MockChatClient client = new MockChatClient(mock(0));
        List<ChatMessage> messages = List.of(ChatMessage.user("蚜虫怎么防治"));

        StreamControl control = new StreamControl();
        List<String> received = new ArrayList<>();
        client.stream(messages, delta -> {
            received.add(delta);
            if (received.size() == 3) {
                control.cancel();
            }
        }, control);
        assertThat(received).hasSize(3);

        StreamControl second = new StreamControl();
        client.stream(messages, delta -> {
            throw new IOException("client aborted");
        }, second);
        assertThat(second.isCancelled()).isTrue();
    }

    @Test
    void streamControlRunsCancelActionOnce() {
        StreamControl control = new StreamControl();
        int[] calls = {0};
        control.onCancel(() -> calls[0]++);
        control.cancel();
        control.cancel();
        assertThat(calls[0]).isEqualTo(1);

        StreamControl already = new StreamControl();
        already.cancel();
        already.onCancel(() -> calls[0]++);
        assertThat(calls[0]).isEqualTo(2);
    }
}
