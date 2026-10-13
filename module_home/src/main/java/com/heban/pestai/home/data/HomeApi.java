package com.heban.pestai.home.data;

import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.network.ApiResponse;

import java.util.List;

import io.reactivex.rxjava3.core.Single;
import retrofit2.http.GET;
import retrofit2.http.Query;

/**
 * 首页接口（公共数据 04「GET user/me」「GET recognitions/recent」），baseUrl 为 {BASE_URL}/pestai/v1/。
 */
public interface HomeApi {

    /** 当前用户资料；令牌失效返回 40101 */
    @GET("user/me")
    Single<ApiResponse<UserVO>> me();

    /** 最近识别，按 created_at 倒序；size 1–10，首页传 3；无记录返回空数组 */
    @GET("recognitions/recent")
    Single<ApiResponse<List<RecordItemVO>>> recent(@Query("size") int size);
}
