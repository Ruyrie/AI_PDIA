package com.heban.pestai.ai.data;

import com.heban.pestai.common.vo.SessionDetailVO;
import com.heban.pestai.common.vo.SessionItemVO;
import com.heban.pestai.common.vo.SessionVO;
import com.heban.pestai.common.vo.SuggestionVO;
import com.heban.pestai.common.vo.UploadVO;
import com.heban.pestai.network.ApiResponse;
import com.heban.pestai.network.PageVO;

import java.util.List;

import io.reactivex.rxjava3.core.Single;
import okhttp3.MultipartBody;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * AI 问答的普通接口（默认 Retrofit，读取超时 30 秒）。流式回答见 {@link AiStreamApi}。
 */
public interface AiApi {

    /** 大家常问（游客不调用） */
    @GET("ai/suggestions")
    Single<ApiResponse<List<SuggestionVO>>> suggestions();

    /** 新建会话：追问识别结果时带 record_id，普通对话为空对象 */
    @POST("ai/sessions")
    Single<ApiResponse<SessionVO>> createSession(@Body CreateSessionReq body);

    /** 上传聊天图片，multipart 字段名 image */
    @Multipart
    @POST("files/image")
    Single<ApiResponse<UploadVO>> uploadImage(@Part MultipartBody.Part image);

    /**
     * 历史对话分页：只返回至少有 1 条消息的会话，按 updated_at 倒序。
     *
     * @param page 从 1 开始
     * @param size 每页条数（App 固定传 20，服务器允许 1–50）
     */
    @GET("ai/sessions")
    Single<ApiResponse<PageVO<SessionItemVO>>> sessions(@Query("page") int page, @Query("size") int size);

    /** 打开历史对话：会话信息、关联记录摘要与全部消息（按时间正序）；不是自己的会话返回 40001 */
    @GET("ai/sessions/{id}")
    Single<ApiResponse<SessionDetailVO>> session(@Path("id") long sessionId);

    /** 删除会话与全部消息（幂等）；响应 data 为 null */
    @DELETE("ai/sessions/{id}")
    Single<ApiResponse<Void>> deleteSession(@Path("id") long sessionId);
}
