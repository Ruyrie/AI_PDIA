package com.heban.pestai.ai.data;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Streaming;

/**
 * 流式回答接口：由 AiModule 用 {@code @Named(NetworkModule.STREAM)} 的 OkHttpClient（读取超时 120 秒、
 * 日志只打印请求头）单独构建的 Retrofit 创建。
 * <p>{@code @Streaming} 让响应体不整体读入内存；返回 {@code Call<ResponseBody>}，
 * 在 Flowable 中同步执行、逐行读取，并可随时 {@code cancel()}（停止生成）。</p>
 */
public interface AiStreamApi {

    @Streaming
    @Headers("Accept: text/event-stream")
    @POST("ai/sessions/{id}/messages")
    Call<ResponseBody> sendMessage(@Path("id") long sessionId, @Body SendMessageReq body);
}
