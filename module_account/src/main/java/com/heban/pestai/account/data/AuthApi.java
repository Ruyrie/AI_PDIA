package com.heban.pestai.account.data;

import com.heban.pestai.account.data.request.ChangePhoneRequest;
import com.heban.pestai.account.data.request.ChangePhoneSmsRequest;
import com.heban.pestai.account.data.request.DeactivateRequest;
import com.heban.pestai.account.data.request.EmptyRequest;
import com.heban.pestai.account.data.request.PasswordLoginRequest;
import com.heban.pestai.account.data.request.PasswordResetRequest;
import com.heban.pestai.account.data.request.PasswordVerifyRequest;
import com.heban.pestai.account.data.request.ProfileRequest;
import com.heban.pestai.account.data.request.RecoverRebindRequest;
import com.heban.pestai.account.data.request.RecoverVerifyRequest;
import com.heban.pestai.account.data.request.RecoveryCodeRequest;
import com.heban.pestai.account.data.request.RegisterRequest;
import com.heban.pestai.account.data.request.SmsLoginRequest;
import com.heban.pestai.account.data.request.SmsSendRequest;
import com.heban.pestai.common.vo.LoginVO;
import com.heban.pestai.common.vo.RebindTicketVO;
import com.heban.pestai.common.vo.RecoveryCodeVO;
import com.heban.pestai.common.vo.ResetTicketVO;
import com.heban.pestai.common.vo.UploadVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.network.ApiResponse;

import io.reactivex.rxjava3.core.Single;
import okhttp3.MultipartBody;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Part;

/**
 * module_account 的 Retrofit 接口（公共数据 03「七、网络层」、04「四、接口总览」）。
 * 路径相对于 {BASE_URL}/pestai/v1/；返回值经 RxResult.transform() / completable() 解包。
 */
public interface AuthApi {

    /** 发送验证码；data 为 null。60 秒内重复发送 42901 */
    @POST("auth/sms/send")
    Single<ApiResponse<Object>> sendSms(@Body SmsSendRequest body);

    /** 密码登录；40102 / 40104 / 40301 */
    @POST("auth/login/password")
    Single<ApiResponse<LoginVO>> loginByPassword(@Body PasswordLoginRequest body);

    /** 验证码登录（未注册自动注册，is_new 为 true）；40103 */
    @POST("auth/login/sms")
    Single<ApiResponse<LoginVO>> loginBySms(@Body SmsLoginRequest body);

    /** 注册；40901 / 40103 / 40001 */
    @POST("auth/register")
    Single<ApiResponse<LoginVO>> register(@Body RegisterRequest body);

    /** 当前用户（闪屏静默校验令牌）；令牌失效 40101 */
    @GET("user/me")
    Single<ApiResponse<UserVO>> me();

    /** 上传头像（multipart 字段 avatar，≤ 1MB） */
    @Multipart
    @POST("user/avatar")
    Single<ApiResponse<UploadVO>> uploadAvatar(@Part MultipartBody.Part avatar);

    /** 完善 / 修改资料 */
    @PUT("user/profile")
    Single<ApiResponse<UserVO>> updateProfile(@Body ProfileRequest body);

    /** 找回密码：校验验证码，换取一次性 reset_ticket；40104 / 40103 */
    @POST("auth/password/verify")
    Single<ApiResponse<ResetTicketVO>> verifyResetCode(@Body PasswordVerifyRequest body);

    /** 重置密码；data 为 null。凭证失效或与旧密码相同均为 40001（以 message 区分） */
    @POST("auth/password/reset")
    Single<ApiResponse<Object>> resetPassword(@Body PasswordResetRequest body);

    // ---------- A09 找回账号（不需要登录） ----------

    /**
     * 用原手机号 + 登录密码 / 账号恢复码验证身份，换取 10 分钟有效的一次性 rebind_ticket。
     * 40104 未注册；40102 账号或密码错误；40105 恢复码错误或已失效（未生成也返回它）；40301 已停用；40001 参数
     */
    @POST("auth/recover/verify")
    Single<ApiResponse<RebindTicketVO>> recoverVerify(@Body RecoverVerifyRequest body);

    /**
     * 凭 rebind_ticket 换绑到新手机号（REBIND 验证码），成功返回新令牌（is_new=false），其他设备的令牌失效。
     * 40001（凭证无效 / 过期 / 已用：「验证已过期，请重新验证身份」；新号与原号相同；恢复码方式缺少合规 new_password）；
     * 40103；40901
     */
    @POST("auth/recover/rebind")
    Single<ApiResponse<LoginVO>> recoverRebind(@Body RecoverRebindRequest body);

    // ---------- 新增：更换手机号、注销账号（需要登录） ----------

    /**
     * 向新手机号发送 CHANGE_PHONE 场景验证码；data 为 null。
     * 40001 格式不对 / 与当前手机号相同（message「新手机号不能与当前手机号相同」）；40901 已注册；42901
     */
    @POST("user/phone/sms")
    Single<ApiResponse<Object>> sendChangePhoneSms(@Body ChangePhoneSmsRequest body);

    /** 更换手机号：返回新令牌与更新后的 user（is_new=false），其他设备的令牌失效；40103 / 40901 / 40001 */
    @PUT("user/phone")
    Single<ApiResponse<LoginVO>> changePhone(@Body ChangePhoneRequest body);

    /** 向当前账号手机号发送 DEACTIVATE 场景验证码（请求体为 {}）；data 为 null；42901 */
    @POST("user/deactivate/sms")
    Single<ApiResponse<Object>> sendDeactivateSms(@Body EmptyRequest body);

    /** 注销账号（立即删除账号与全部数据）；data 为 null；40103 */
    @POST("user/deactivate")
    Single<ApiResponse<Object>> deactivate(@Body DeactivateRequest body);

    // ---------- A12 账号恢复码（需要登录） ----------

    /**
     * 恢复码状态：generated、created_at、verify_required（10 分钟内刚通过短信验证过手机号时为 false）；
     * recovery_code 为空
     */
    @GET("user/recovery-code")
    Single<ApiResponse<RecoveryCodeVO>> recoveryCodeStatus();

    /** 向当前账号手机号发送 RECOVERY 场景验证码（请求体为 {}）；data 为 null；42901 */
    @POST("user/recovery-code/sms")
    Single<ApiResponse<Object>> sendRecoveryCodeSms(@Body EmptyRequest body);

    /**
     * 生成 / 重新生成恢复码（旧码立即失效）；recovery_code 明文只在这里返回一次。
     * code 可选：不带 code 时仅在刚通过短信验证的 10 分钟内允许，否则 40001「请先验证当前手机号」；带 code 时错误为 40103
     */
    @POST("user/recovery-code")
    Single<ApiResponse<RecoveryCodeVO>> generateRecoveryCode(@Body RecoveryCodeRequest body);
}
