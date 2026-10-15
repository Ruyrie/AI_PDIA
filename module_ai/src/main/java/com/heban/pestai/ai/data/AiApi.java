package com.heban.pestai.ai.data;

import com.heban.pestai.common.vo.SessionDetailVO;
import com.heban.pestai.common.vo.SessionItemVO;
import com.heban.pestai.common.vo.SessionVO;
import com.heban.pestai.common.vo.UploadVO;
import com.heban.pestai.network.ApiResponse;
import com.heban.pestai.network.PageVO;

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

    /**
     * 语音识别（输入栏语音输入的「按住 说话」）：multipart 字段名 audio，WAV（PCM 16 位、16kHz、单声道），≤ 60 秒、≤ 2MB。
     * 没识别出文字 40001「没有听清，请再说一遍」；超过频率 42901；识别服务不可用 50201。音频只用于本次识别，不保存。
     */
    @Multipart
    @POST("ai/asr")
    Single<ApiResponse<AsrVO>> recognizeSpeech(@Part MultipartBody.Part audio);
}
