package com.heban.pestai.account.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.account.data.request.ChangePhoneRequest;
import com.heban.pestai.account.data.request.ChangePhoneSmsRequest;
import com.heban.pestai.account.data.request.DeactivateRequest;
import com.heban.pestai.account.data.request.EmptyRequest;
import com.heban.pestai.account.data.request.PasswordResetRequest;
import com.heban.pestai.account.data.request.ProfileRequest;
import com.heban.pestai.account.data.request.RecoverRebindRequest;
import com.heban.pestai.account.data.request.RecoverVerifyRequest;
import com.heban.pestai.account.data.request.RecoveryCodeRequest;
import com.heban.pestai.account.testutil.FakeAuthApi;
import com.heban.pestai.account.testutil.RxTestRule;
import com.heban.pestai.account.testutil.TestEnv;
import com.heban.pestai.common.constant.RecoverMethod;
import com.heban.pestai.common.constant.SmsScene;
import com.heban.pestai.common.vo.RecoveryCodeVO;
import com.heban.pestai.common.session.UserSession;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

import io.reactivex.rxjava3.core.Single;

public class AccountRepositoryTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    private TestEnv env;

    @Before
    public void setUp() {
        env = new TestEnv();
    }

    @Test
    public void verifyToken_40101_returnsFalseAndClearsLogin() {
        env.withToken("token");
        env.context.prefs.edit().putString(UserSession.KEY_USER_JSON, "{\"nickname\":\"x\"}").apply();
        env.api.meResult = FakeAuthApi.fail(ApiException.UNAUTHORIZED, null);

        Boolean valid = env.repository.verifyToken().blockingGet();
        assertFalse(valid);
        assertNull(env.token());
        assertNull(env.userJson());
    }

    @Test
    public void verifyToken_ioError_returnsTrueAndKeepsToken() {
        env.withToken("token");
        env.api.meResult = Single.error(new IOException("offline"));
        assertTrue(env.repository.verifyToken().blockingGet());
        assertEquals("token", env.token());
    }

    @Test
    public void verifyToken_serverError_returnsTrue() {
        env.withToken("token");
        env.api.meResult = FakeAuthApi.fail(ApiException.SERVER_ERROR, "服务器开小差了，请稍后再试");
        assertTrue(env.repository.verifyToken().blockingGet());
        assertEquals("token", env.token());
    }

    @Test
    public void verifyToken_success_refreshesUserJson() {
        env.withToken("token");
        assertTrue(env.repository.verifyToken().blockingGet());
        String json = env.userJson();
        assertNotNull(json);
        assertTrue(json.contains("\"nickname\":\"老李种菜\""));
        assertTrue(json.contains("\"crops\":\"番茄,黄瓜,小麦\""));
    }

    @Test
    public void login_savesTokenAndUserAndClearsGuest() {
        env.session.setGuest(true);
        env.repository.loginByPassword("138 0000 5678", "abc12345").blockingGet();
        assertEquals("13800005678", env.api.lastPasswordLogin.getPhone());
        assertEquals("token-123", env.token());
        assertTrue(env.userJson().contains("农友5678"));
        assertFalse(env.session.isGuest());
    }

    @Test
    public void login_failure_doesNotSaveSession() {
        env.api.loginByPasswordResult = FakeAuthApi.fail(ApiException.WRONG_PASSWORD, "账号或密码错误");
        try {
            env.repository.loginByPassword("13800005678", "abc12345").blockingGet();
        } catch (ApiException e) {
            assertEquals(ApiException.WRONG_PASSWORD, e.getCode());
        }
        assertNull(env.token());
    }

    @Test
    public void sendCode_normalizesPhoneAndScene() {
        env.repository.sendCode("139 0000 1111", SmsScene.REBIND).blockingAwait();
        assertEquals("13900001111", env.api.lastSms.getPhone());
        assertEquals("REBIND", env.api.lastSms.getScene());
    }

    @Test
    public void enterGuest_removesTokenAndMarksGuest() {
        env.withToken("token");
        env.repository.enterGuest();
        assertNull(env.token());
        assertTrue(env.session.isGuest());
    }

    @Test
    public void onboardingFlag_survivesLogout() {
        env.repository.setOnboardingDone();
        env.withToken("token");
        env.repository.logout();
        assertTrue(env.repository.isOnboardingDone());
        assertFalse(env.repository.hasToken());
    }

    @Test
    public void updateProfile_overwritesUserJson() {
        env.repository.updateProfile(new ProfileRequest("老李种菜", null, "种植户", "番茄"))
                .blockingGet();
        assertTrue(env.userJson().contains("老李种菜"));
    }

    @Test
    public void requestBodies_serializeWithSnakeCase() {
        String reset = env.gson.toJson(new PasswordResetRequest("13800005678", "ticket", "abc12345"));
        assertTrue(reset.contains("\"reset_ticket\":\"ticket\""));
        assertTrue(reset.contains("\"new_password\":\"abc12345\""));

        assertEquals("{\"old_phone\":\"13800005678\",\"method\":\"PASSWORD\",\"password\":\"abc12345\"}",
                env.gson.toJson(new RecoverVerifyRequest("13800005678", RecoverMethod.PASSWORD, "abc12345", null)));
        assertEquals("{\"old_phone\":\"13800005678\",\"method\":\"RECOVERY_CODE\",\"recovery_code\":\"ABCDEFGHJKLM\"}",
                env.gson.toJson(new RecoverVerifyRequest("13800005678", RecoverMethod.RECOVERY_CODE, null,
                        "ABCDEFGHJKLM")));
        assertEquals("{\"old_phone\":\"13800005678\",\"rebind_ticket\":\"t\",\"new_phone\":\"13900001111\","
                        + "\"code\":\"123456\"}",
                env.gson.toJson(new RecoverRebindRequest("13800005678", "t", "13900001111", "123456", null)));
        assertTrue(env.gson.toJson(new RecoverRebindRequest("13800005678", "t", "13900001111", "123456", "abc12345"))
                .contains("\"new_password\":\"abc12345\""));
        assertEquals("{\"code\":\"123456\"}", env.gson.toJson(new RecoveryCodeRequest("123456")));

        assertEquals("{\"new_phone\":\"13900001111\"}", env.gson.toJson(new ChangePhoneSmsRequest("13900001111")));
        assertEquals("{\"new_phone\":\"13900001111\",\"code\":\"123456\"}",
                env.gson.toJson(new ChangePhoneRequest("13900001111", "123456")));
        assertEquals("{}", env.gson.toJson(EmptyRequest.INSTANCE));
        assertEquals("{\"code\":\"123456\"}", env.gson.toJson(new DeactivateRequest("123456")));

        String profile = env.gson.toJson(new ProfileRequest("老李", null, "种植户", ""));
        assertFalse(profile.contains("avatar_url"));  // 未上传头像时服务器保留原值
        assertFalse(profile.contains("region"));      // A06 不再收集地区，服务器保留原值
    }

    // ---------- 更换手机号、注销账号 ----------

    @Test
    public void currentPhone_fromCachedUser() {
        assertNull(env.repository.currentPhone());
        env.withLogin();
        assertEquals("13800005678", env.repository.currentPhone());
    }

    @Test
    public void sendChangePhoneCode_normalizesNewPhone() {
        env.repository.sendChangePhoneCode("139 0000 1111").blockingAwait();
        assertEquals(1, env.api.changePhoneSmsCount);
        assertEquals("13900001111", env.api.lastChangePhoneSms.getNewPhone());
        assertEquals(0, env.api.sendSmsCount);   // 不走 auth/sms/send
    }

    @Test
    public void changePhone_savesNewTokenAndUser() {
        env.withLogin();
        env.repository.changePhone("139 0000 1111", "123456").blockingGet();
        assertEquals("13900001111", env.api.lastChangePhone.getNewPhone());
        assertEquals("123456", env.api.lastChangePhone.getCode());
        assertEquals("token-new", env.token());
        assertEquals("13900001111", env.session.getUser().getPhone());
        assertTrue(env.session.isLoggedIn());
    }

    @Test
    public void changePhone_failure_keepsOldLogin() {
        env.withLogin();
        env.api.changePhoneResult = FakeAuthApi.fail(ApiException.PHONE_REGISTERED, "该手机号已注册");
        try {
            env.repository.changePhone("13900001111", "123456").blockingGet();
            fail("应抛出 ApiException");
        } catch (ApiException e) {
            assertEquals(ApiException.PHONE_REGISTERED, e.getCode());
        }
        assertEquals("token-123", env.token());
        assertEquals("13800005678", env.repository.currentPhone());
    }

    @Test
    public void deactivate_sendsCodeOnly() {
        env.withLogin();
        env.repository.sendDeactivateCode().blockingAwait();
        assertEquals(1, env.api.deactivateSmsCount);
        assertNotNull(env.api.lastDeactivateSms);

        env.repository.deactivate("123456").blockingAwait();
        assertEquals("123456", env.api.lastDeactivate.getCode());
        // 本地登录态由 ViewModel 通过 IUserService.logout() 清除
        assertEquals("token-123", env.token());
    }

    // ---------- 找回账号、账号恢复码 ----------

    @Test
    public void verifyRecover_password_sendsPasswordOnly() {
        env.repository.verifyRecover("138 0000 5678", RecoverMethod.PASSWORD, "abc12345").blockingGet();
        RecoverVerifyRequest body = env.api.lastRecoverVerify;
        assertEquals("13800005678", body.getOldPhone());
        assertEquals(RecoverMethod.PASSWORD, body.getMethod());
        assertEquals("abc12345", body.getPassword());
        assertNull(body.getRecoveryCode());
    }

    @Test
    public void verifyRecover_recoveryCode_sendsNormalizedCodeOnly() {
        env.repository.verifyRecover("13800005678", RecoverMethod.RECOVERY_CODE, "abcd-efgh-jklm").blockingGet();
        RecoverVerifyRequest body = env.api.lastRecoverVerify;
        assertEquals(RecoverMethod.RECOVERY_CODE, body.getMethod());
        assertEquals("ABCDEFGHJKLM", body.getRecoveryCode());
        assertNull(body.getPassword());
    }

    @Test
    public void rebind_savesNewTokenAndUser() {
        env.repository.rebind("138 0000 5678", "ticket", "139 0000 1111", "123456", null).blockingGet();
        RecoverRebindRequest body = env.api.lastRecoverRebind;
        assertEquals("13800005678", body.getOldPhone());
        assertEquals("ticket", body.getRebindTicket());
        assertEquals("13900001111", body.getNewPhone());
        assertEquals("123456", body.getCode());
        assertNull(body.getNewPassword());
        assertEquals("token-new", env.token());
        assertEquals("13900001111", env.session.getUser().getPhone());
        assertTrue(env.session.isLoggedIn());
    }

    @Test
    public void rebind_failure_doesNotSaveSession() {
        env.api.recoverRebindResult = FakeAuthApi.fail(ApiException.SMS_CODE_INVALID, null);
        try {
            env.repository.rebind("13800005678", "ticket", "13900001111", "123456", "abc12345").blockingGet();
            fail("应抛出 ApiException");
        } catch (ApiException e) {
            assertEquals(ApiException.SMS_CODE_INVALID, e.getCode());
        }
        assertNull(env.token());
    }

    @Test
    public void recoveryCode_statusSmsAndGenerate() {
        env.withLogin();
        RecoveryCodeVO status = env.repository.recoveryCodeStatus().blockingGet();
        assertFalse(status.isGenerated());

        env.repository.sendRecoveryCodeSms().blockingAwait();
        assertEquals(1, env.api.recoveryCodeSmsCount);
        assertEquals("{}", env.gson.toJson(env.api.lastRecoveryCodeSms));
        assertEquals(0, env.api.sendSmsCount);   // 不走 auth/sms/send

        RecoveryCodeVO generated = env.repository.generateRecoveryCode("123456").blockingGet();
        assertEquals("123456", env.api.lastGenerateRecoveryCode.getCode());
        assertEquals("ABCD-EFGH-JKLM", generated.getRecoveryCode());
    }

    @Test
    public void generateRecoveryCode_withoutCode_sendsEmptyBody() {
        env.withLogin();
        env.repository.generateRecoveryCode(null).blockingGet();
        assertNull(env.api.lastGenerateRecoveryCode.getCode());
        assertEquals("{}", env.gson.toJson(env.api.lastGenerateRecoveryCode));
    }

    @Test
    public void recoveryCodeStatus_parsesVerifyRequired() {
        RecoveryCodeVO recent = env.gson.fromJson("{\"generated\":false,\"verify_required\":false}",
                RecoveryCodeVO.class);
        assertFalse(recent.isVerifyRequired());
        RecoveryCodeVO stale = env.gson.fromJson(
                "{\"generated\":true,\"created_at\":\"2026-10-13 09:30:00\",\"verify_required\":true}",
                RecoveryCodeVO.class);
        assertTrue(stale.isVerifyRequired());
        assertTrue(stale.isGenerated());
    }
}
