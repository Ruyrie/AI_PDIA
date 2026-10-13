package com.heban.pestai.record.data;

import androidx.annotation.Nullable;

import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.network.ApiResponse;
import com.heban.pestai.network.PageVO;

import java.util.List;

import io.reactivex.rxjava3.core.Single;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * 识别记录接口（模块07 技术准备文档 2.6「App 端接口封装」），路径相对 {BASE_URL}/pestai/v1/。
 */
public interface RecordApi {

    /**
     * 识别记录分页：按 created_at 倒序。
     *
     * @param mode PEST / CROP；为 null 时 Retrofit 不拼接该参数，即「全部」
     * @param page 从 1 开始
     * @param size 每页条数（App 固定传 20，服务器允许 1–50）
     */
    @GET("recognitions")
    Single<ApiResponse<PageVO<RecordItemVO>>> list(@Nullable @Query("mode") String mode,
                                                   @Query("page") int page,
                                                   @Query("size") int size);

    /** 删除记录（服务器逻辑删除，幂等）；响应 data 为 null */
    @DELETE("recognitions/{id}")
    Single<ApiResponse<Void>> delete(@Path("id") long id);

    /** 最近识别（IRecordService.recent() 使用），size 1–10 */
    @GET("recognitions/recent")
    Single<ApiResponse<List<RecordItemVO>>> recent(@Query("size") int size);

    /**
     * 单条记录摘要（IRecordService.get() 使用）。接口实际返回 RecognitionVO，
     * 这里用 RecordItemVO 接收，Gson 只解析同名的 9 个字段，忽略其余详情字段。
     */
    @GET("recognitions/{id}")
    Single<ApiResponse<RecordItemVO>> summary(@Path("id") long id);
}
