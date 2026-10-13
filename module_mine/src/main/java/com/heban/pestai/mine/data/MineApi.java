package com.heban.pestai.mine.data;

import com.heban.pestai.common.vo.AppVersionVO;
import com.heban.pestai.common.vo.RecoveryCodeVO;
import com.heban.pestai.common.vo.UserStatsVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.network.ApiResponse;

import io.reactivex.rxjava3.core.Single;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

/**
 * module_mine 的 Retrofit 接口（公共数据 03「MineApi」，模块09 技术准备文档 2.6）。
 * baseUrl 为 {BASE_URL}/pestai/v1/，AuthInterceptor 自动携带 Bearer 令牌。
 */
public interface MineApi {

    /** GET user/me：当前用户资料 */
    @GET("user/me")
    Single<ApiResponse<UserVO>> me();

    /** GET user/stats：我的页统计；没有记录时 last_record 为 null */
    @GET("user/stats")
    Single<ApiResponse<UserStatsVO>> stats();

    /** GET user/recovery-code：账号恢复码状态（generated、created_at；不含明文），M02「账号恢复码」行使用 */
    @GET("user/recovery-code")
    Single<ApiResponse<RecoveryCodeVO>> recoveryCode();

    /** POST auth/logout：作废当前账号令牌（token_version 加 1），data 为 null */
    @POST("auth/logout")
    Single<ApiResponse<Object>> logout();

    /** GET app/version（预留，v1.0 不调用）：查询最新版本；data 可能为 null（表中没有版本） */
    @GET("app/version")
    Single<ApiResponse<AppVersionVO>> latestVersion(@Query("version_code") long versionCode);
}
