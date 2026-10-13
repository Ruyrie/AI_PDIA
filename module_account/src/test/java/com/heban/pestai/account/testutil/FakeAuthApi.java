package com.heban.pestai.account.testutil;

import com.heban.pestai.account.data.AuthApi;
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
import com.heban.pestai.common.constant.RecoverMethod;
import com.heban.pestai.common.vo.LoginVO;
import com.heban.pestai.common.vo.RebindTicketVO;
import com.heban.pestai.common.vo.RecoveryCodeVO;
import com.heban.pestai.common.vo.ResetTicketVO;
import com.heban.pestai.common.vo.UploadVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.network.ApiResponse;

import java.util.Date;

import io.reactivex.rxjava3.core.Single;
import okhttp3.MultipartBody;

/** 假 AuthApi：每个接口返回可配置的结果，并记录调用次数与最后一次请求体 */
public class FakeAuthApi implements AuthApi {

    public Single<ApiResponse<Object>> sendSmsResult = ok(null);
    public Single<ApiResponse<LoginVO>> loginByPasswordResult = ok(login(false));
    public Single<ApiResponse<LoginVO>> loginBySmsResult = ok(login(false));
    public Single<ApiResponse<LoginVO>> registerResult = ok(login(false));
    public Single<ApiResponse<UserVO>> meResult = ok(user("老李种菜"));
    public Single<ApiResponse<UploadVO>> uploadAvatarResult = ok(new UploadVO());
    public Single<ApiResponse<UserVO>> updateProfileResult = ok(user("老李种菜"));
    public Single<ApiResponse<ResetTicketVO>> verifyResetCodeResult = ok(ticket(600));
    public Single<ApiResponse<Object>> resetPasswordResult = ok(null);
    public Single<ApiResponse<RebindTicketVO>> recoverVerifyResult = ok(rebindTicket(600, RecoverMethod.PASSWORD));
    public Single<ApiResponse<LoginVO>> recoverRebindResult = ok(changedLogin("13900001111"));
    public Single<ApiResponse<RecoveryCodeVO>> recoveryCodeStatusResult = ok(recoveryStatus(false, null));
    public Single<ApiResponse<Object>> recoveryCodeSmsResult = ok(null);
    public Single<ApiResponse<RecoveryCodeVO>> generateRecoveryCodeResult =
            ok(recoveryGenerated("ABCD-EFGH-JKLM", new Date()));
    public Single<ApiResponse<Object>> changePhoneSmsResult = ok(null);
    public Single<ApiResponse<LoginVO>> changePhoneResult = ok(changedLogin("13900001111"));
    public Single<ApiResponse<Object>> deactivateSmsResult = ok(null);
    public Single<ApiResponse<Object>> deactivateResult = ok(null);

    public int sendSmsCount;
    public int loginByPasswordCount;
    public int loginBySmsCount;
    public int registerCount;
    public int meCount;
    public int updateProfileCount;
    public int verifyResetCodeCount;
    public int resetPasswordCount;
    public int recoverVerifyCount;
    public int recoverRebindCount;
    public int recoveryCodeStatusCount;
    public int recoveryCodeSmsCount;
    public int generateRecoveryCodeCount;
    public int changePhoneSmsCount;
    public int changePhoneCount;
    public int deactivateSmsCount;
    public int deactivateCount;

    public SmsSendRequest lastSms;
    public PasswordLoginRequest lastPasswordLogin;
    public SmsLoginRequest lastSmsLogin;
    public RegisterRequest lastRegister;
    public ProfileRequest lastProfile;
    public PasswordVerifyRequest lastVerify;
    public PasswordResetRequest lastReset;
    public RecoverVerifyRequest lastRecoverVerify;
    public RecoverRebindRequest lastRecoverRebind;
    public EmptyRequest lastRecoveryCodeSms;
    public RecoveryCodeRequest lastGenerateRecoveryCode;
    public ChangePhoneSmsRequest lastChangePhoneSms;
    public ChangePhoneRequest lastChangePhone;
    public EmptyRequest lastDeactivateSms;
    public DeactivateRequest lastDeactivate;

    // ---------- 构造数据 ----------

    public static <T> Single<ApiResponse<T>> ok(T data) {
        return Single.just(new ApiResponse<>(0, "ok", data));
    }

    public static <T> Single<ApiResponse<T>> fail(int code, String message) {
        return Single.just(new ApiResponse<>(code, message, null));
    }

    public static UserVO user(String nickname) {
        UserVO user = new UserVO();
        user.setId(10001L);
        user.setPhone("13800005678");
        user.setNickname(nickname);
        user.setRegion("江苏省 南京市 江宁区");
        user.setIdentity("种植户");
        user.setCrops("番茄,黄瓜,小麦");
        return user;
    }

    public static LoginVO login(boolean isNew) {
        LoginVO vo = new LoginVO();
        vo.setToken("token-123");
        vo.setUser(user("农友5678"));
        vo.setNew(isNew);
        return vo;
    }

    /** PUT user/phone 成功：新令牌 + 手机号已更新的用户 */
    public static LoginVO changedLogin(String newPhone) {
        LoginVO vo = new LoginVO();
        vo.setToken("token-new");
        UserVO user = user("老李种菜");
        user.setPhone(newPhone);
        vo.setUser(user);
        vo.setNew(false);
        return vo;
    }

    public static ResetTicketVO ticket(int expireIn) {
        ResetTicketVO vo = new ResetTicketVO();
        vo.setResetTicket("8f3c2a9e5b7d4f108f3c2a9e5b7d4f10");
        vo.setExpireIn(expireIn);
        return vo;
    }

    public static RebindTicketVO rebindTicket(int expireIn, String method) {
        RebindTicketVO vo = new RebindTicketVO();
        vo.setRebindTicket("5d1e7a3c9b2f4e608d1e7a3c9b2f4e60");
        vo.setExpireIn(expireIn);
        vo.setMethod(method);
        return vo;
    }

    /** GET user/recovery-code：不含明文；需要短信验证码（verify_required=true，超过 10 分钟没有短信验证） */
    public static RecoveryCodeVO recoveryStatus(boolean generated, Date createdAt) {
        return recoveryStatus(generated, createdAt, true);
    }

    /** GET user/recovery-code：verifyRequired 为 false 表示 10 分钟内刚通过短信验证，可以免验证码直接生成 */
    public static RecoveryCodeVO recoveryStatus(boolean generated, Date createdAt, boolean verifyRequired) {
        RecoveryCodeVO vo = new RecoveryCodeVO();
        vo.setGenerated(generated);
        vo.setCreatedAt(createdAt);
        vo.setVerifyRequired(verifyRequired);
        return vo;
    }

    /** POST user/recovery-code：明文只返回这一次 */
    public static RecoveryCodeVO recoveryGenerated(String code, Date createdAt) {
        RecoveryCodeVO vo = recoveryStatus(true, createdAt);
        vo.setRecoveryCode(code);
        return vo;
    }

    // ---------- AuthApi ----------

    @Override
    public Single<ApiResponse<Object>> sendSms(SmsSendRequest body) {
        sendSmsCount++;
        lastSms = body;
        return sendSmsResult;
    }

    @Override
    public Single<ApiResponse<LoginVO>> loginByPassword(PasswordLoginRequest body) {
        loginByPasswordCount++;
        lastPasswordLogin = body;
        return loginByPasswordResult;
    }

    @Override
    public Single<ApiResponse<LoginVO>> loginBySms(SmsLoginRequest body) {
        loginBySmsCount++;
        lastSmsLogin = body;
        return loginBySmsResult;
    }

    @Override
    public Single<ApiResponse<LoginVO>> register(RegisterRequest body) {
        registerCount++;
        lastRegister = body;
        return registerResult;
    }

    @Override
    public Single<ApiResponse<UserVO>> me() {
        meCount++;
        return meResult;
    }

    @Override
    public Single<ApiResponse<UploadVO>> uploadAvatar(MultipartBody.Part avatar) {
        return uploadAvatarResult;
    }

    @Override
    public Single<ApiResponse<UserVO>> updateProfile(ProfileRequest body) {
        updateProfileCount++;
        lastProfile = body;
        return updateProfileResult;
    }

    @Override
    public Single<ApiResponse<ResetTicketVO>> verifyResetCode(PasswordVerifyRequest body) {
        verifyResetCodeCount++;
        lastVerify = body;
        return verifyResetCodeResult;
    }

    @Override
    public Single<ApiResponse<Object>> resetPassword(PasswordResetRequest body) {
        resetPasswordCount++;
        lastReset = body;
        return resetPasswordResult;
    }

    @Override
    public Single<ApiResponse<RebindTicketVO>> recoverVerify(RecoverVerifyRequest body) {
        recoverVerifyCount++;
        lastRecoverVerify = body;
        return recoverVerifyResult;
    }

    @Override
    public Single<ApiResponse<LoginVO>> recoverRebind(RecoverRebindRequest body) {
        recoverRebindCount++;
        lastRecoverRebind = body;
        return recoverRebindResult;
    }

    @Override
    public Single<ApiResponse<RecoveryCodeVO>> recoveryCodeStatus() {
        recoveryCodeStatusCount++;
        return recoveryCodeStatusResult;
    }

    @Override
    public Single<ApiResponse<Object>> sendRecoveryCodeSms(EmptyRequest body) {
        recoveryCodeSmsCount++;
        lastRecoveryCodeSms = body;
        return recoveryCodeSmsResult;
    }

    @Override
    public Single<ApiResponse<RecoveryCodeVO>> generateRecoveryCode(RecoveryCodeRequest body) {
        generateRecoveryCodeCount++;
        lastGenerateRecoveryCode = body;
        return generateRecoveryCodeResult;
    }

    @Override
    public Single<ApiResponse<Object>> sendChangePhoneSms(ChangePhoneSmsRequest body) {
        changePhoneSmsCount++;
        lastChangePhoneSms = body;
        return changePhoneSmsResult;
    }

    @Override
    public Single<ApiResponse<LoginVO>> changePhone(ChangePhoneRequest body) {
        changePhoneCount++;
        lastChangePhone = body;
        return changePhoneResult;
    }

    @Override
    public Single<ApiResponse<Object>> sendDeactivateSms(EmptyRequest body) {
        deactivateSmsCount++;
        lastDeactivateSms = body;
        return deactivateSmsResult;
    }

    @Override
    public Single<ApiResponse<Object>> deactivate(DeactivateRequest body) {
        deactivateCount++;
        lastDeactivate = body;
        return deactivateResult;
    }
}
