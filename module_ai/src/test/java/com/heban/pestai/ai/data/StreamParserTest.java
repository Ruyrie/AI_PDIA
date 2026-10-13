package com.heban.pestai.ai.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.heban.pestai.network.ApiException;

import org.junit.Test;

import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import okhttp3.MediaType;
import okhttp3.ResponseBody;

public class StreamParserTest {

    private static final MediaType EVENT_STREAM = MediaType.get("text/event-stream;charset=UTF-8");
    private static final MediaType JSON = MediaType.get("application/json;charset=UTF-8");

    private final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private TestSubscriber<StreamChunk> parse(String body, MediaType type) {
        ResponseBody responseBody = ResponseBody.create(body, type);
        return Flowable.<StreamChunk>create(emitter -> {
            try (ResponseBody b = responseBody) {
                StreamParser.parse(b, gson, emitter);
            } catch (Throwable t) {
                emitter.tryOnError(t);
            }
        }, BackpressureStrategy.BUFFER).test();
    }

    private static StreamChunk c(String text) {
        return StreamChunk.content(text);
    }

    private static StreamChunk r(String text) {
        return StreamChunk.reasoning(text);
    }

    // ==================== 行解析 ====================

    @Test
    public void dataOf_withOrWithoutSpace() {
        assertEquals("片段", StreamParser.dataOf("data: 片段"));
        assertEquals("片段", StreamParser.dataOf("data:片段"));
        // 只去掉前缀后的一个空格，片段本身的前导空格保留
        assertEquals(" 3 天内", StreamParser.dataOf("data:  3 天内"));
        assertEquals("", StreamParser.dataOf("data:"));
    }

    @Test
    public void dataOf_nonDataLine_returnsNull() {
        assertNull(StreamParser.dataOf(""));
        assertNull(StreamParser.dataOf(": keep-alive"));
        assertNull(StreamParser.dataOf("event: message"));
        assertNull(StreamParser.dataOf("id: 1"));
        assertNull(StreamParser.dataOf(" data: x"));
        assertNull(StreamParser.dataOf(null));
    }

    @Test
    public void eventOf() {
        assertEquals("reasoning", StreamParser.eventOf("event: reasoning"));
        assertEquals("reasoning", StreamParser.eventOf("event:reasoning "));
        assertNull(StreamParser.eventOf("data: event: reasoning"));
        assertNull(StreamParser.eventOf(null));
    }

    // ==================== 正文（原有规则） ====================

    @Test
    public void parse_chunksThenDone() {
        TestSubscriber<StreamChunk> ts = parse("data: 你好\n\ndata: ，世界\n\ndata: [DONE]\n\n", EVENT_STREAM);
        ts.assertValues(c("你好"), c("，世界"));
        ts.assertComplete();
        ts.assertNoErrors();
    }

    @Test
    public void parse_ignoresBlankAndNonDataLines() {
        TestSubscriber<StreamChunk> ts = parse(
                ": comment\nevent: message\n\ndata: A\nid: 3\n\r\ndata: B\ndata: [DONE]\n", EVENT_STREAM);
        ts.assertValues(c("A"), c("B"));
        ts.assertComplete();
    }

    @Test
    public void parse_restoresEscapedNewline() {
        TestSubscriber<StreamChunk> ts = parse("data: 处理建议\\n1. 通风\ndata: [DONE]\n", EVENT_STREAM);
        ts.assertValues(c("处理建议\n1. 通风"));
        ts.assertComplete();
    }

    @Test
    public void parse_withoutDone_emitsStreamBroken() {
        TestSubscriber<StreamChunk> ts = parse("data: 从照片看，\n\ndata: 建议今天就", EVENT_STREAM);
        ts.assertValues(c("从照片看，"), c("建议今天就"));
        ts.assertError(StreamBrokenException.class);
    }

    @Test
    public void parse_errorLine_emitsApiException() {
        TestSubscriber<StreamChunk> ts = parse(
                "data: 结合识别结果\n\ndata: [ERROR]{\"code\":50201,\"message\":\"AI 服务暂不可用，请稍后再试\"}\n\n",
                EVENT_STREAM);
        ts.assertValues(c("结合识别结果"));
        ts.assertError(e -> e instanceof ApiException && ((ApiException) e).getCode() == ApiException.AI_UNAVAILABLE);
    }

    @Test
    public void parse_errorJsonOnNextLine() {
        TestSubscriber<StreamChunk> ts = parse("data: [ERROR]\ndata: {\"code\":50201,\"message\":\"x\"}\n",
                EVENT_STREAM);
        ts.assertNoValues();
        ts.assertError(e -> e instanceof ApiException && ((ApiException) e).getCode() == 50201);
    }

    @Test
    public void parse_errorWithoutJson_defaultsTo50201() {
        TestSubscriber<StreamChunk> ts = parse("data: [ERROR] oops\n", EVENT_STREAM);
        ts.assertError(e -> e instanceof ApiException && ((ApiException) e).getCode() == 50201);
    }

    @Test
    public void parse_jsonBody_beforeStream_emitsBusinessError() {
        TestSubscriber<StreamChunk> ts = parse(
                "{\"code\":42901,\"message\":\"操作太频繁，请稍后再试\",\"data\":null}", JSON);
        ts.assertNoValues();
        ts.assertError(e -> e instanceof ApiException && ((ApiException) e).getCode() == ApiException.TOO_FREQUENT
                && "操作太频繁，请稍后再试".equals(e.getMessage()));
    }

    @Test
    public void parse_emptyChunks_areSkipped() {
        TestSubscriber<StreamChunk> ts = parse("data:\ndata: \ndata: x\ndata: [DONE]\n", EVENT_STREAM);
        ts.assertValues(c("x"));
        ts.assertComplete();
    }

    @Test
    public void parse_stopsAfterDone() {
        TestSubscriber<StreamChunk> ts = parse("data: a\ndata: [DONE]\ndata: b\n", EVENT_STREAM);
        ts.assertValues(c("a"));
        ts.assertComplete();
    }

    // ==================== 思考片段（event: reasoning） ====================

    @Test
    public void parse_reasoningEvents_thenAnswer() {
        TestSubscriber<StreamChunk> ts = parse(
                "event: reasoning\ndata: 用户问的是\n\n"
                        + "event: reasoning\ndata: 黄瓜霜霉病\\n先看症状\n\n"
                        + "data: 结合识别结果，\n\n"
                        + "data: 建议通风。\n\n"
                        + "data: [DONE]\n\n",
                EVENT_STREAM);
        ts.assertValues(r("用户问的是"), r("黄瓜霜霉病\n先看症状"), c("结合识别结果，"), c("建议通风。"));
        ts.assertComplete();
    }

    @Test
    public void parse_eventAppliesOnlyToNextDataLine() {
        // event 只作用到紧随其后的一条 data 行；空行结束事件，之后的 data 行是正文
        TestSubscriber<StreamChunk> ts = parse(
                "event: reasoning\ndata: 思考\ndata: 正文A\n\nevent: reasoning\n\ndata: 正文B\ndata: [DONE]\n",
                EVENT_STREAM);
        ts.assertValues(r("思考"), c("正文A"), c("正文B"));
        ts.assertComplete();
    }

    @Test
    public void parse_keepAliveComments_ignoredAnywhere() {
        // 保活注释「:thinking」（冒号后无空格）可能出现在任何位置，包括 event 行与 data 行之间、正文输出中
        TestSubscriber<StreamChunk> ts = parse(
                ":thinking\n\n"
                        + "event: reasoning\n:thinking\ndata: 思考中\n\n"
                        + ":thinking\n\n"
                        + "data: 第一段\n\n"
                        + ":thinking\n"
                        + ": thinking\n"
                        + "data: 第二段\n\n"
                        + "data: [DONE]\n\n",
                EVENT_STREAM);
        ts.assertValues(r("思考中"), c("第一段"), c("第二段"));
        ts.assertComplete();
    }

    @Test
    public void parse_otherEventName_isAnswer() {
        TestSubscriber<StreamChunk> ts = parse("event: message\ndata: 正文\ndata: [DONE]\n", EVENT_STREAM);
        ts.assertValues(c("正文"));
        ts.assertComplete();
    }

    @Test
    public void parse_emptyReasoning_skipped_andBrokenDuringReasoning() {
        TestSubscriber<StreamChunk> ts = parse("event: reasoning\ndata:\n\nevent: reasoning\ndata: 想一想\n\n",
                EVENT_STREAM);
        ts.assertValues(r("想一想"));
        ts.assertError(StreamBrokenException.class);
    }

    @Test
    public void parse_errorAfterReasoning() {
        TestSubscriber<StreamChunk> ts = parse(
                "event: reasoning\ndata: 想一想\n\ndata: [ERROR]{\"code\":50201,\"message\":\"x\"}\n\n", EVENT_STREAM);
        ts.assertValues(r("想一想"));
        ts.assertError(e -> e instanceof ApiException && ((ApiException) e).getCode() == 50201);
    }
}
