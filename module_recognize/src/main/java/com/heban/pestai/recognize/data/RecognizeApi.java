package com.heban.pestai.recognize.data;

import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.network.ApiResponse;

import io.reactivex.rxjava3.core.Single;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;

/**
 * 识别相关接口（公共数据 04「四、接口清单」），baseUrl 为 {BASE_URL}/pestai/v1/。
 */
public interface RecognizeApi {

    /**
     * 上传照片并识别（模块05）：multipart 的 image（JPEG，≤ 2MB）与 mode（PEST / CROP）。
     * 错误码：42201 照片不清晰、50201 识别服务暂不可用、50401 识别超时、40001 参数错误。
     */
    @Multipart
    @POST("recognitions")
    Single<ApiResponse<RecognitionVO>> recognize(@Part MultipartBody.Part image, @Part("mode") RequestBody mode);

    /** 识别详情（模块06）；记录不存在、已删除或不属于当前用户时返回 40001 */
    @GET("recognitions/{id}")
    Single<ApiResponse<RecognitionVO>> getDetail(@Path("id") long id);

    /** 用服务器上的原照片重新识别（模块06）；成功返回同一 id 的新结果 */
    @POST("recognitions/{id}/retry")
    Single<ApiResponse<RecognitionVO>> retry(@Path("id") long id);
}
