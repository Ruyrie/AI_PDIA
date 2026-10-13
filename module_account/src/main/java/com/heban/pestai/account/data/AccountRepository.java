package com.heban.pestai.account.data;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
import com.heban.pestai.account.util.PhoneRules;
import com.heban.pestai.account.util.RecoveryCodeRules;
import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.common.session.UserSession;
import com.heban.pestai.common.constant.RecoverMethod;
import com.heban.pestai.common.vo.LoginVO;
import com.heban.pestai.common.vo.RebindTicketVO;
import com.heban.pestai.common.vo.RecoveryCodeVO;
import com.heban.pestai.common.vo.ResetTicketVO;
import com.heban.pestai.common.vo.UploadVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.network.RxResult;

import java.io.File;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

/**
 * module_account 的数据仓库（模块01–03 共用）：封装 AuthApi 与 UserSession，向 ViewModel 返回 VO。
 * <ul>
 *     <li>网络请求、图片压缩都在 io 线程；结果由 ViewModel 切回主线程。</li>
 *     <li>登录 / 注册 / 找回账号成功统一在这里保存登录态（doOnSuccess → UserSession.saveLogin），不会遗漏。</li>
 *     <li>ViewModel 只依赖本类，单元测试时用假 AuthApi 构造即可。</li>
 * </ul>
 */
@Singleton
public class AccountRepository {

    /** 闪屏校验令牌的最长等待 */
    public static final long VERIFY_TIMEOUT_SECONDS = 2;
    private static final String AVATAR_CACHE_DIR = "account_avatar";
    private static final MediaType JPEG = MediaType.get("image/jpeg");

    private final AuthApi api;
    private final UserSession session;
    private final ImageCompressor compressor;
    private final Context context;

    @Inject
    public AccountRepository(AuthApi api, UserSession session, ImageCompressor compressor, Context context) {
        this.api = api;
        this.session = session;
        this.compressor = compressor;
        this.context = context;
    }

    // ---------- 本地状态（SharedPreferences：pest_session） ----------

    public boolean isOnboardingDone() {
        return session.isOnboardingDone();
    }

    /** 离开引导页时写入；apply() 异步落盘，内存立即生效 */
    public void setOnboardingDone() {
        session.setOnboardingDone(true);
    }

    /** 本地是否有令牌（只判断有无，有效性以服务器校验为准） */
    public boolean hasToken() {
        String token = session.getToken();
        return token != null && !token.isEmpty();
    }

    /** 本地缓存的用户资料（user_json），解析失败时 UserSession 会删除损坏的缓存并返回 null */
    @Nullable
    public UserVO cachedUser() {
        return session.getUser();
    }

    /** 游客浏览：移除 token、user_json（防止残留旧账号数据），is_guest 写为 true */
    public void enterGuest() {
        session.clear();
        session.setGuest(true);
    }

    /** 清除 token、user_json、is_guest（保留 onboarding_done 等本机设置） */
    public void logout() {
        session.clear();
    }

    // ---------- 模块01：闪屏 ----------

    /**
     * GET user/me 静默校验令牌（2 秒超时）：返回 UserVO → 刷新 user_json 并返回 true；
     * 服务器明确返回 40101（或 HTTP 401）→ 清除登录态并返回 false；断网、超时、50001 等「暂时无法确认」→ true。
     */
    @NonNull
    public Single<Boolean> verifyToken() {
        return api.me()
                .subscribeOn(Schedulers.io())
                .compose(RxResult.<UserVO>transform())
                .doOnSuccess(session::saveUser)
                .map(user -> Boolean.TRUE)
                .timeout(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .onErrorResumeNext(e -> {
                    if (ApiException.codeOf(e) == ApiException.UNAUTHORIZED) {
                        session.clearLogin();
                        return Single.just(Boolean.FALSE);
                    }
                    return Single.just(Boolean.TRUE);
                });
    }

    // ---------- 模块02：登录注册 ----------

    /** 发送验证码，scene 见 SmsScene */
    @NonNull
    public Completable sendCode(@Nullable String phone, @NonNull String scene) {
        return api.sendSms(new SmsSendRequest(PhoneRules.normalize(phone), scene))
                .subscribeOn(Schedulers.io())
                .to(RxResult.completable());
    }

    @NonNull
    public Single<LoginVO> loginByPassword(@Nullable String phone, @NonNull String password) {
        return api.loginByPassword(new PasswordLoginRequest(PhoneRules.normalize(phone), password))
                .subscribeOn(Schedulers.io())
                .compose(RxResult.<LoginVO>transform())
                .doOnSuccess(this::saveLogin);
    }

    @NonNull
    public Single<LoginVO> loginBySms(@Nullable String phone, @NonNull String code) {
        return api.loginBySms(new SmsLoginRequest(PhoneRules.normalize(phone), code))
                .subscribeOn(Schedulers.io())
                .compose(RxResult.<LoginVO>transform())
                .doOnSuccess(this::saveLogin);
    }

    @NonNull
    public Single<LoginVO> register(@Nullable String phone, @NonNull String code, @NonNull String password) {
        return api.register(new RegisterRequest(PhoneRules.normalize(phone), code, password))
                .subscribeOn(Schedulers.io())
                .compose(RxResult.<LoginVO>transform())
                .doOnSuccess(this::saveLogin);
    }

    /**
     * 上传头像：ImageCompressor 压缩到 ≤ 1MB（按 EXIF 摆正）→ multipart 字段 avatar 上传；无论成败都删除临时文件。
     *
     * @param uri 照片选择器返回的 content Uri 字符串
     */
    @NonNull
    public Single<UploadVO> uploadAvatar(@NonNull String uri) {
        return Single.fromCallable(() -> compressor.compress(context.getContentResolver(), Uri.parse(uri),
                        new File(context.getCacheDir(), AVATAR_CACHE_DIR), ImageCompressor.AVATAR_MAX_BYTES))
                .subscribeOn(Schedulers.io())
                .flatMap(result -> {
                    RequestBody body = RequestBody.create(result.file, JPEG);
                    MultipartBody.Part part = MultipartBody.Part.createFormData("avatar", "avatar.jpg", body);
                    return api.uploadAvatar(part)
                            .compose(RxResult.<UploadVO>transform())
                            .doFinally(() -> deleteQuietly(result.file));
                });
    }

    /** PUT user/profile；成功后用返回的 UserVO 覆盖 user_json 并通知其他组件 */
    @NonNull
    public Single<UserVO> updateProfile(@NonNull ProfileRequest request) {
        return api.updateProfile(request)
                .subscribeOn(Schedulers.io())
                .compose(RxResult.<UserVO>transform())
                .doOnSuccess(session::saveUser);
    }

    // ---------- 模块03：找回密码、找回账号 ----------

    /** 校验 RESET 场景验证码，换取一次性重置凭证 */
    @NonNull
    public Single<ResetTicketVO> verifyResetCode(@Nullable String phone, @NonNull String code) {
        return api.verifyResetCode(new PasswordVerifyRequest(PhoneRules.normalize(phone), code))
                .subscribeOn(Schedulers.io())
                .compose(RxResult.<ResetTicketVO>transform());
    }

    /** 凭重置凭证设置新密码 */
    @NonNull
    public Completable resetPassword(@Nullable String phone, @NonNull String resetTicket, @NonNull String newPassword) {
        return api.resetPassword(new PasswordResetRequest(PhoneRules.normalize(phone), resetTicket, newPassword))
                .subscribeOn(Schedulers.io())
                .to(RxResult.completable());
    }

    /**
     * A09 第 1 步：原手机号 + 登录密码（{@link RecoverMethod#PASSWORD}）或账号恢复码（{@link RecoverMethod#RECOVERY_CODE}）
     * 验证身份，换取一次性 rebind_ticket。只提交所选方式对应的一项；恢复码去掉连字符、转大写后提交。
     */
    @NonNull
    public Single<RebindTicketVO> verifyRecover(@Nullable String oldPhone, @NonNull String method,
                                                @Nullable String secret) {
        boolean byCode = RecoverMethod.RECOVERY_CODE.equals(method);
        RecoverVerifyRequest request = new RecoverVerifyRequest(PhoneRules.normalize(oldPhone), method,
                byCode ? null : secret,
                byCode ? RecoveryCodeRules.normalize(secret) : null);
        return api.recoverVerify(request)
                .subscribeOn(Schedulers.io())
                .compose(RxResult.<RebindTicketVO>transform());
    }

    /**
     * A09 第 2 步：凭 rebind_ticket 换绑到新手机号。成功后服务器 token_version + 1 并签发新令牌，
     * 这里用 saveLogin 保存（本机直接登录，其他设备失效）。newPassword 为 null 时不修改密码。
     */
    @NonNull
    public Single<LoginVO> rebind(@Nullable String oldPhone, @NonNull String rebindTicket, @Nullable String newPhone,
                                  @NonNull String code, @Nullable String newPassword) {
        return api.recoverRebind(new RecoverRebindRequest(PhoneRules.normalize(oldPhone), rebindTicket,
                        PhoneRules.normalize(newPhone), code, newPassword))
                .subscribeOn(Schedulers.io())
                .compose(RxResult.<LoginVO>transform())
                .doOnSuccess(this::saveLogin);
    }

    // ---------- 新增：更换手机号（A10）、注销账号（A11） ----------

    /** 当前账号的手机号（user_json 中的 phone，去空格）；未登录或没有资料时为 null */
    @Nullable
    public String currentPhone() {
        UserVO user = session.getUser();
        String phone = user == null ? "" : PhoneRules.normalize(user.getPhone());
        return phone.isEmpty() ? null : phone;
    }

    /** POST user/phone/sms：向新手机号发送 CHANGE_PHONE 场景验证码 */
    @NonNull
    public Completable sendChangePhoneCode(@Nullable String newPhone) {
        return api.sendChangePhoneSms(new ChangePhoneSmsRequest(PhoneRules.normalize(newPhone)))
                .subscribeOn(Schedulers.io())
                .to(RxResult.completable());
    }

    /**
     * PUT user/phone：换绑成功后服务器 token_version + 1 并签发新令牌，这里用 saveLogin 保存新 token 与 user_json，
     * 本机保持登录（其他设备的令牌失效），并通过 userChanges() 通知设置页、我的页刷新手机号。
     */
    @NonNull
    public Single<LoginVO> changePhone(@Nullable String newPhone, @NonNull String code) {
        return api.changePhone(new ChangePhoneRequest(PhoneRules.normalize(newPhone), code))
                .subscribeOn(Schedulers.io())
                .compose(RxResult.<LoginVO>transform())
                .doOnSuccess(this::saveLogin);
    }

    /** POST user/deactivate/sms：验证码由服务器发往当前账号手机号 */
    @NonNull
    public Completable sendDeactivateCode() {
        return api.sendDeactivateSms(EmptyRequest.INSTANCE)
                .subscribeOn(Schedulers.io())
                .to(RxResult.completable());
    }

    /** POST user/deactivate：成功后账号与全部数据已删除；本地登录态由调用方通过 IUserService.logout() 清除 */
    @NonNull
    public Completable deactivate(@NonNull String code) {
        return api.deactivate(new DeactivateRequest(code))
                .subscribeOn(Schedulers.io())
                .to(RxResult.completable());
    }

    // ---------- 新增：账号恢复码（A12） ----------

    /**
     * GET user/recovery-code：是否已生成、生成时间（不含明文）；verify_required 为 false 表示 10 分钟内刚通过短信验证过
     * 手机号，生成时可以不带验证码
     */
    @NonNull
    public Single<RecoveryCodeVO> recoveryCodeStatus() {
        return api.recoveryCodeStatus()
                .subscribeOn(Schedulers.io())
                .compose(RxResult.<RecoveryCodeVO>transform());
    }

    /** POST user/recovery-code/sms：验证码由服务器发往当前账号手机号（RECOVERY 场景） */
    @NonNull
    public Completable sendRecoveryCodeSms() {
        return api.sendRecoveryCodeSms(EmptyRequest.INSTANCE)
                .subscribeOn(Schedulers.io())
                .to(RxResult.completable());
    }

    /**
     * POST user/recovery-code：生成 / 重新生成恢复码，旧码立即失效；返回的 recovery_code 明文只有这一次。
     *
     * @param code RECOVERY 验证码；null 表示免验证直接生成（请求体为 {}，仅在刚通过短信验证的 10 分钟内有效，
     *             否则服务器返回 40001「请先验证当前手机号」）
     */
    @NonNull
    public Single<RecoveryCodeVO> generateRecoveryCode(@Nullable String code) {
        return api.generateRecoveryCode(new RecoveryCodeRequest(code))
                .subscribeOn(Schedulers.io())
                .compose(RxResult.<RecoveryCodeVO>transform());
    }

    // ---------- 内部 ----------

    private void saveLogin(@NonNull LoginVO login) {
        session.saveLogin(login);
    }

    private static void deleteQuietly(@NonNull File file) {
        //noinspection ResultOfMethodCallIgnored 临时文件，删除失败由系统清理缓存目录
        file.delete();
    }
}
