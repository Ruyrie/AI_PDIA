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
        for (String sample : MockVisionClient.PEST_SAMPLES) {
            ParsedVision p = VisionResultParser.parse(sample, Mode.PEST);
            assertThat(p.isSuccess()).isTrue();
            assertThat(p.severity()).isNotNull();
        }
        for (String sample : MockVisionClient.CROP_SAMPLES) {
            ParsedVision p = VisionResultParser.parse(sample, Mode.CROP);
            assertThat(p.isSuccess()).isTrue();
            assertThat(p.severity()).isNull();
        }
        assertThat(VisionResultParser.parse(MockVisionClient.IMAGE_BAD, Mode.PEST).isSuccess()).isFalse();
    }

    @Test
    void sameImageAlwaysGivesSameResult() throws Exception {
        MockVisionClient client = new MockVisionClient(mock(0));
        byte[] image = "pretend-jpeg-bytes".getBytes(StandardCharsets.UTF_8);
        String first = client.recognize(image, "image/jpeg", Mode.PEST, Duration.ofSeconds(25));
        String second = client.recognize(image, "image/jpeg", Mode.PEST, Duration.ofSeconds(25));
        assertThat(first).isEqualTo(second);
        assertThat(MockVisionClient.PEST_SAMPLES).contains(first);
        assertThat(client.isMock()).isTrue();
    }

    @Test
    void differentImagesRotateThroughSamples() throws Exception {
        MockVisionClient client = new MockVisionClient(mock(0));
        List<String> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            results.add(client.recognize(("img-" + i).getBytes(StandardCharsets.UTF_8), "image/jpeg", Mode.CROP,
                    Duration.ofSeconds(25)));
        }
        assertThat(results.stream().distinct().count()).isGreaterThan(1);
        assertThat(MockVisionClient.CROP_SAMPLES).containsAll(results);
    }

    @Test
    void imageBadPercentControlsSimulatedBlurryPhotos() throws Exception {
        MockVisionClient alwaysBad = new MockVisionClient(mock(100));
        assertThat(alwaysBad.recognize(new byte[]{1, 2, 3}, "image/jpeg", Mode.PEST, Duration.ofSeconds(25)))
                .isEqualTo(MockVisionClient.IMAGE_BAD);
    }

    @Test
    void delayLongerThanBudgetSimulatesTimeout() {
        PestaiProperties.Mock slow = mock(0);
        slow.setVisionDelayMs(5_000);
        MockVisionClient client = new MockVisionClient(slow);
        assertThatThrownBy(() -> client.recognize(new byte[]{1}, "image/jpeg", Mode.PEST, Duration.ofMillis(10)))
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
