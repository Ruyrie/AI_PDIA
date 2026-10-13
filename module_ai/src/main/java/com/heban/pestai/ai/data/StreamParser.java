package com.heban.pestai.ai.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.network.ApiResponse;

import java.io.IOException;

import io.reactivex.rxjava3.core.FlowableEmitter;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * 逐行解析 POST ai/sessions/{id}/messages 的流式响应（text/event-stream），发出带类型的 {@link StreamChunk}。
 * <ul>
 *     <li>「event: reasoning」：紧随其后的一条 data 行是思考片段（同样还原转义的 \n），发出 {@link StreamChunk#reasoning}；
 *     空行结束一个事件，event 只作用到下一条 data 行；</li>
 *     <li>「data: 片段」：去掉前缀 data: 及其后一个空格，把转义的 \n 还原为换行，发出 {@link StreamChunk#content}；</li>
 *     <li>注释行（以「:」开头，如保活「:thinking」，可能在任何时候出现，包括正文输出中）、其他非 data 行：跳过；</li>
 *     <li>「data: [DONE]」：onComplete；</li>
 *     <li>「data: [ERROR]」+ 错误 JSON：按 code（如 50201）发出 ApiException；</li>
 *     <li>没收到 [DONE] 就断开：发出 {@link StreamBrokenException}；</li>
 *     <li>Content-Type 为 json：流开始前的业务错误（40001、42901 等），整体解析为 ApiResponse。</li>
 * </ul>
 * 每读一行前检查 emitter 是否已取消；错误一律用 tryOnError，停止生成后的异常被直接忽略。
 */
public final class StreamParser {

    public static final String DATA = "data:";
    /** SSE 注释行前缀（保活「:thinking」） */
    public static final String COMMENT = ":";
    public static final String EVENT = "event:";
    public static final String EVENT_REASONING = "reasoning";
    public static final String DONE = "[DONE]";
    public static final String ERROR = "[ERROR]";

    private StreamParser() {
    }

    /** 调用方负责关闭 body（try-with-resources） */
    public static void parse(@NonNull ResponseBody body, @NonNull Gson gson,
                             @NonNull FlowableEmitter<StreamChunk> emitter) throws IOException {
        MediaType type = body.contentType();
        if (type != null && "json".equalsIgnoreCase(type.subtype())) {
            emitter.tryOnError(toApiException(body.string(), gson, ApiException.SERVER_ERROR));
            return;
        }
        BufferedSource source = body.source();
        // [ERROR] 后面的 JSON 不在同一行时，取下一条 data 行
        boolean awaitingErrorJson = false;
        // 上一行是 event: reasoning，下一条 data 行是思考片段
        boolean reasoningNext = false;
        while (!emitter.isCancelled()) {
            String line = source.readUtf8Line();
            if (line == null) {
                emitter.tryOnError(awaitingErrorJson
                        ? ApiException.of(ApiException.AI_UNAVAILABLE)
                        : new StreamBrokenException());
                return;
            }
            if (line.isEmpty()) {
                // 空行：一个事件结束
                reasoningNext = false;
                continue;
            }
            if (line.startsWith(COMMENT)) {
                // 保活注释不影响事件状态（event: reasoning 与其 data 行之间也可能夹着注释）
                continue;
            }
            String event = eventOf(line);
            if (event != null) {
                reasoningNext = EVENT_REASONING.equals(event);
                continue;
            }
            String data = dataOf(line);
            if (data == null) {
                continue;
            }
            if (awaitingErrorJson) {
                emitter.tryOnError(toApiException(data, gson, ApiException.AI_UNAVAILABLE));
                return;
            }
            if (reasoningNext) {
                reasoningNext = false;
                String chunk = unescape(data);
                if (!chunk.isEmpty()) {
                    emitter.onNext(StreamChunk.reasoning(chunk));
                }
                continue;
            }
            String trimmed = data.trim();
            if (DONE.equals(trimmed)) {
                emitter.onComplete();
                return;
            }
            if (trimmed.startsWith(ERROR)) {
                String json = trimmed.substring(ERROR.length()).trim();
                if (json.isEmpty()) {
                    awaitingErrorJson = true;
                    continue;
                }
                emitter.tryOnError(toApiException(json, gson, ApiException.AI_UNAVAILABLE));
                return;
            }
            String chunk = unescape(data);
            if (!chunk.isEmpty()) {
                emitter.onNext(StreamChunk.content(chunk));
            }
        }
    }

    /**
     * 从「data: 片段」或「data:片段」中取出「片段」（只去掉前缀后的一个空格）；不是 data 行返回 null。
     */
    @Nullable
    static String dataOf(@Nullable String line) {
        if (line == null || !line.startsWith(DATA)) {
            return null;
        }
        String value = line.substring(DATA.length());
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    /** 从「event: reasoning」中取出事件名（去掉首尾空白）；不是 event 行返回 null */
    @Nullable
    static String eventOf(@Nullable String line) {
        if (line == null || !line.startsWith(EVENT)) {
            return null;
        }
        return line.substring(EVENT.length()).trim();
    }

    /** 后端把片段内的换行转义为 \n，这里还原 */
    @NonNull
    static String unescape(@NonNull String data) {
        return data.replace("\\n", "\n");
    }

    @NonNull
    static ApiException toApiException(@Nullable String json, @NonNull Gson gson, int fallbackCode) {
        try {
            ApiResponse<?> response = gson.fromJson(json, ApiResponse.class);
            if (response != null && response.getCode() != ApiException.OK) {
                return ApiException.of(response.getCode(), response.getMessage());
            }
        } catch (RuntimeException ignored) {
            // 不是合法 JSON：按兜底错误码处理
        }
        return ApiException.of(fallbackCode);
    }
}
