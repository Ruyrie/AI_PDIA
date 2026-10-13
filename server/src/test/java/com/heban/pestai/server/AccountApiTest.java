package com.heban.pestai.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.RecoverMethod;
import com.heban.pestai.server.common.Rules;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.config.WebMvcConfig;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.security.JwtTokenProvider;
import com.heban.pestai.server.service.AccountService;
import com.heban.pestai.server.service.RecoverService;
import com.heban.pestai.server.service.RecoveryCodeService;
import com.heban.pestai.server.service.SmsService;
import com.heban.pestai.server.vo.LoginVO;
import com.heban.pestai.server.vo.RebindTicketVO;
import com.heban.pestai.server.vo.RecoveryCodeVO;
import com.heban.pestai.server.vo.UserVO;

/**
 * 更换手机号、注销账号、找回账号、账号恢复码的接口层测试：启动完整 Spring 容器，业务 Service 与 UserMapper 全部 Mock，
 * 数据源指向不可达地址，确保任何情况下都不会连接本机数据库。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/pestai_unit_test_unreachable",
        "spring.datasource.username=nobody",
        "spring.datasource.password=",
        "vision.api-key=",
        "chat.api-key=",
        "sms.provider=log",
        "pestai.file-root=target/test-files",
        "pestai.public-base-url=http://10.0.2.2:8080",
        "pestai.jwt.secret=integration-test-secret-0123456789abcdef"
})
@AutoConfigureMockMvc
class AccountApiTest {

    private static final long USER_ID = 10001L;
    private static final String OLD_PHONE = "13800005678";
    private static final String NEW_PHONE = "13900001111";
    private static final String TICKET = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtTokenProvider tokens;

    @MockBean
    private UserMapper userMapper;
    @MockBean
    private AccountService accountService;
    @MockBean
    private SmsService smsService;
    @MockBean
    private RecoverService recoverService;
    @MockBean
    private RecoveryCodeService recoveryCodeService;

    private String bearer;

    @BeforeEach
    void setUp() {
        User auth = new User();
        auth.setId(USER_ID);
        auth.setTokenVersion(0);
        auth.setStatus(User.STATUS_NORMAL);
        when(userMapper.selectAuthInfo(USER_ID)).thenReturn(auth);
        bearer = "Bearer " + tokens.issue(USER_ID, 0);
    }

    // ------------------------------------------------------------------ auth/sms/send 场景

    @Test
    void publicSmsSendRejectsLoggedInScenesAndRemovedAppealScene() throws Exception {
        for (String scene : new String[] {"CHANGE_PHONE", "DEACTIVATE", "RECOVERY", "APPEAL"}) {
            mvc.perform(json(post("/pestai/v1/auth/sms/send"), "{\"phone\":\"13900001111\",\"scene\":\"" + scene + "\"}"))
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value("参数错误"));
        }
        verify(smsService, never()).send(anyString(), any());

        mvc.perform(json(post("/pestai/v1/auth/sms/send"), "{\"phone\":\"13900001111\",\"scene\":\"LOGIN\"}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(smsService).send(NEW_PHONE, SmsScene.LOGIN);
    }

    @Test
    void publicSmsSendAcceptsRebindAndPassesRegisteredError() throws Exception {
        mvc.perform(json(post("/pestai/v1/auth/sms/send"), "{\"phone\":\"13900001111\",\"scene\":\"REBIND\"}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(smsService).send(NEW_PHONE, SmsScene.REBIND);

        doThrow(new BizException(ErrorCode.PHONE_REGISTERED, AccountService.PHONE_TAKEN))
                .when(smsService).send(OLD_PHONE, SmsScene.REBIND);
        mvc.perform(json(post("/pestai/v1/auth/sms/send"), "{\"phone\":\"13800005678\",\"scene\":\"REBIND\"}"))
                .andExpect(jsonPath("$.code").value(40901));
    }

    // ------------------------------------------------------------------ 登录要求与白名单

    @Test
    void accountApisRequireLogin() throws Exception {
        mvc.perform(json(post("/pestai/v1/user/phone/sms"), "{\"new_phone\":\"13900001111\"}"))
                .andExpect(jsonPath("$.code").value(40101));
        mvc.perform(json(put("/pestai/v1/user/phone"), "{\"new_phone\":\"13900001111\",\"code\":\"123456\"}"))
                .andExpect(jsonPath("$.code").value(40101));
        mvc.perform(json(post("/pestai/v1/user/deactivate/sms"), "{}"))
                .andExpect(jsonPath("$.code").value(40101));
        mvc.perform(json(post("/pestai/v1/user/deactivate"), "{\"code\":\"123456\"}"))
                .andExpect(jsonPath("$.code").value(40101));
        mvc.perform(get("/pestai/v1/user/recovery-code"))
                .andExpect(jsonPath("$.code").value(40101));
        mvc.perform(json(post("/pestai/v1/user/recovery-code/sms"), "{}"))
                .andExpect(jsonPath("$.code").value(40101));
        mvc.perform(json(post("/pestai/v1/user/recovery-code"), "{\"code\":\"123456\"}"))
                .andExpect(jsonPath("$.code").value(40101));
        verifyNoInteractions(accountService, recoveryCodeService);
    }

    @Test
    void recoverApisAreWhitelistedAndAppealIsGone() throws Exception {
        assertThat(WebMvcConfig.WHITELIST).contains("auth/recover/verify", "auth/recover/rebind")
                .doesNotContain("auth/appeal")
                .noneMatch(path -> path.startsWith("admin"))
                .noneMatch(path -> path.startsWith("user/"));

        when(recoverService.verify(OLD_PHONE, RecoverMethod.PASSWORD, "abc12345", null))
                .thenReturn(new RebindTicketVO(TICKET, 600, "PASSWORD"));
        // 不带令牌即可访问
        mvc.perform(json(post("/pestai/v1/auth/recover/verify"),
                        "{\"old_phone\":\"13800005678\",\"method\":\"PASSWORD\",\"password\":\"abc12345\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rebind_ticket").value(TICKET))
                .andExpect(jsonPath("$.data.expire_in").value(600))
                .andExpect(jsonPath("$.data.method").value("PASSWORD"));
    }

    // ------------------------------------------------------------------ auth/recover/verify

    @Test
    void recoverVerifyValidatesRequest() throws Exception {
        mvc.perform(json(post("/pestai/v1/auth/recover/verify"),
                        "{\"old_phone\":\"1380000567\",\"method\":\"PASSWORD\",\"password\":\"abc12345\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("原手机号格式不正确"));
        mvc.perform(json(post("/pestai/v1/auth/recover/verify"), "{\"old_phone\":\"13800005678\",\"password\":\"abc12345\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("请选择验证方式"));
        mvc.perform(json(post("/pestai/v1/auth/recover/verify"),
                        "{\"old_phone\":\"13800005678\",\"method\":\"SMS\",\"password\":\"abc12345\"}"))
                .andExpect(jsonPath("$.code").value(40001));
        // 方式与凭据不配套：选了密码却只填了恢复码，反之亦然
        mvc.perform(json(post("/pestai/v1/auth/recover/verify"),
                        "{\"old_phone\":\"13800005678\",\"method\":\"PASSWORD\",\"recovery_code\":\"ABCD-EFGH-JKLM\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("请输入登录密码"));
        mvc.perform(json(post("/pestai/v1/auth/recover/verify"),
                        "{\"old_phone\":\"13800005678\",\"method\":\"RECOVERY_CODE\",\"password\":\"abc12345\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("请输入恢复码"));
        verifyNoInteractions(recoverService);
    }

    @Test
    void recoverVerifyPassesRecoveryCodeAndBusinessErrors() throws Exception {
        when(recoverService.verify(OLD_PHONE, RecoverMethod.RECOVERY_CODE, null, "abcd efgh-jklm"))
                .thenReturn(new RebindTicketVO(TICKET, 600, "RECOVERY_CODE"));
        mvc.perform(json(post("/pestai/v1/auth/recover/verify"),
                        "{\"old_phone\":\"13800005678\",\"method\":\"RECOVERY_CODE\",\"recovery_code\":\"abcd efgh-jklm\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.method").value("RECOVERY_CODE"));

        when(recoverService.verify(OLD_PHONE, RecoverMethod.RECOVERY_CODE, null, "WRONG-CODE-0000"))
                .thenThrow(new BizException(ErrorCode.RECOVERY_CODE_INVALID));
        mvc.perform(json(post("/pestai/v1/auth/recover/verify"),
                        "{\"old_phone\":\"13800005678\",\"method\":\"RECOVERY_CODE\",\"recovery_code\":\"WRONG-CODE-0000\"}"))
                .andExpect(jsonPath("$.code").value(40105))
                .andExpect(jsonPath("$.message").value("恢复码错误或已失效"));

        when(recoverService.verify(OLD_PHONE, RecoverMethod.PASSWORD, "wrong999", null))
                .thenThrow(new BizException(ErrorCode.WRONG_PASSWORD));
        mvc.perform(json(post("/pestai/v1/auth/recover/verify"),
                        "{\"old_phone\":\"13800005678\",\"method\":\"PASSWORD\",\"password\":\"wrong999\"}"))
                .andExpect(jsonPath("$.code").value(40102))
                .andExpect(jsonPath("$.message").value("账号或密码错误"));

        when(recoverService.verify(OLD_PHONE, RecoverMethod.PASSWORD, "abc12345", null))
                .thenThrow(new BizException(ErrorCode.ACCOUNT_DISABLED));
        mvc.perform(json(post("/pestai/v1/auth/recover/verify"),
                        "{\"old_phone\":\"13800005678\",\"method\":\"PASSWORD\",\"password\":\"abc12345\"}"))
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("该账号已停用"));
    }

    // ------------------------------------------------------------------ auth/recover/rebind

    @Test
    void rebindValidatesTicketPhonesAndOptionalPassword() throws Exception {
        String base = "{\"old_phone\":\"13800005678\",\"new_phone\":\"13900001111\",\"code\":\"048215\"";
        mvc.perform(json(post("/pestai/v1/auth/recover/rebind"), base + ",\"rebind_ticket\":\"short\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(RecoverService.TICKET_EXPIRED));
        mvc.perform(json(post("/pestai/v1/auth/recover/rebind"), base + "}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(RecoverService.TICKET_EXPIRED));
        mvc.perform(json(post("/pestai/v1/auth/recover/rebind"),
                        base + ",\"rebind_ticket\":\"" + TICKET + "\",\"new_password\":\"12345678\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(Rules.PASSWORD_MESSAGE));
        mvc.perform(json(post("/pestai/v1/auth/recover/rebind"),
                        "{\"old_phone\":\"13800005678\",\"new_phone\":\"1390000111\",\"code\":\"048215\","
                                + "\"rebind_ticket\":\"" + TICKET + "\"}"))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("新手机号格式不正确"));
        verifyNoInteractions(recoverService);

        // new_password 可省略：原样以 null 交给业务层（是否必填由凭证的验证方式决定）
        UserVO user = new UserVO(USER_ID, NEW_PHONE, "老李种菜", null, "江苏省 南京市 江宁区", "种植户", null);
        when(recoverService.rebind(OLD_PHONE, TICKET, NEW_PHONE, "048215", null))
                .thenReturn(new LoginVO("new-token", user, false));
        mvc.perform(json(post("/pestai/v1/auth/recover/rebind"), base + ",\"rebind_ticket\":\"" + TICKET + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("new-token"))
                .andExpect(jsonPath("$.data.user.phone").value(NEW_PHONE))
                .andExpect(jsonPath("$.data.is_new").value(false));
        verify(recoverService).rebind(OLD_PHONE, TICKET, NEW_PHONE, "048215", null);

        when(recoverService.rebind(OLD_PHONE, TICKET, NEW_PHONE, "048215", "newpass123"))
                .thenReturn(new LoginVO("token-2", user, false));
        mvc.perform(json(post("/pestai/v1/auth/recover/rebind"),
                        base + ",\"rebind_ticket\":\"" + TICKET + "\",\"new_password\":\"newpass123\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("token-2"));
    }

    @Test
    void rebindBusinessErrorsKeepCodeAndMessage() throws Exception {
        String body = "{\"old_phone\":\"13800005678\",\"new_phone\":\"13900001111\",\"code\":\"048215\","
                + "\"rebind_ticket\":\"" + TICKET + "\"}";
        when(recoverService.rebind(OLD_PHONE, TICKET, NEW_PHONE, "048215", null))
                .thenThrow(BizException.param(RecoverService.TICKET_EXPIRED))
                .thenThrow(BizException.param(RecoverService.NEW_PASSWORD_REQUIRED))
                .thenThrow(new BizException(ErrorCode.PHONE_REGISTERED, AccountService.PHONE_TAKEN))
                .thenThrow(new BizException(ErrorCode.SMS_CODE_INVALID));
        mvc.perform(json(post("/pestai/v1/auth/recover/rebind"), body))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("验证已过期，请重新验证身份"));
        mvc.perform(json(post("/pestai/v1/auth/recover/rebind"), body))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(RecoverService.NEW_PASSWORD_REQUIRED));
        mvc.perform(json(post("/pestai/v1/auth/recover/rebind"), body))
                .andExpect(jsonPath("$.code").value(40901));
        mvc.perform(json(post("/pestai/v1/auth/recover/rebind"), body))
                .andExpect(jsonPath("$.code").value(40103));
    }

    // ------------------------------------------------------------------ user/recovery-code

    @Test
    void recoveryCodeStatusUsesUserFromToken() throws Exception {
        when(recoveryCodeService.status(USER_ID)).thenReturn(new RecoveryCodeVO(false, null, null, false));
        mvc.perform(auth(get("/pestai/v1/user/recovery-code").param("user_id", "999")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.generated").value(false))
                .andExpect(jsonPath("$.data.created_at").isEmpty())
                .andExpect(jsonPath("$.data.recovery_code").isEmpty())
                .andExpect(jsonPath("$.data.verify_required").value(false));

        when(recoveryCodeService.status(USER_ID))
                .thenReturn(new RecoveryCodeVO(true, LocalDateTime.of(2026, 10, 13, 9, 30, 0), null, true));
        mvc.perform(auth(get("/pestai/v1/user/recovery-code")))
                .andExpect(jsonPath("$.data.generated").value(true))
                .andExpect(jsonPath("$.data.created_at").value("2026-10-13 09:30:00"))
                .andExpect(jsonPath("$.data.recovery_code").isEmpty());
    }

    @Test
    void recoveryCodeSmsAcceptsEmptyOrMissingBody() throws Exception {
        mvc.perform(auth(json(post("/pestai/v1/user/recovery-code/sms"), "{}")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(auth(post("/pestai/v1/user/recovery-code/sms")))
                .andExpect(jsonPath("$.code").value(0));
        verify(recoveryCodeService, times(2)).sendCode(USER_ID);

        doThrow(new BizException(ErrorCode.TOO_FREQUENT)).when(recoveryCodeService).sendCode(USER_ID);
        mvc.perform(auth(json(post("/pestai/v1/user/recovery-code/sms"), "{}")))
                .andExpect(jsonPath("$.code").value(42901));
    }

    @Test
    void generateRecoveryCodeValidatesCodeAndReturnsPlainTextOnce() throws Exception {
        mvc.perform(auth(json(post("/pestai/v1/user/recovery-code"), "{\"code\":\"12345\"}")))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(Rules.SMS_CODE_MESSAGE));
        verify(recoveryCodeService, never()).generate(anyLong(), anyString());

        // 不带 code（{} 或省略请求体）：交给服务层按「10 分钟内刚验证过短信」判断
        when(recoveryCodeService.generate(USER_ID, null))
                .thenReturn(new RecoveryCodeVO(true, LocalDateTime.of(2026, 10, 13, 9, 31, 0), "WXYZ-2345-ABCD", false));
        mvc.perform(auth(json(post("/pestai/v1/user/recovery-code"), "{}")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.recovery_code").value("WXYZ-2345-ABCD"));
        mvc.perform(auth(post("/pestai/v1/user/recovery-code")))
                .andExpect(jsonPath("$.code").value(0));
        verify(recoveryCodeService, times(2)).generate(USER_ID, null);

        when(recoveryCodeService.generate(USER_ID, "048215"))
                .thenReturn(new RecoveryCodeVO(true, LocalDateTime.of(2026, 10, 13, 9, 30, 0), "ABCD-EFGH-JKLM", true));
        mvc.perform(auth(json(post("/pestai/v1/user/recovery-code"), "{\"code\":\"048215\"}")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.generated").value(true))
                .andExpect(jsonPath("$.data.created_at").value("2026-10-13 09:30:00"))
                .andExpect(jsonPath("$.data.recovery_code").value("ABCD-EFGH-JKLM"));

        when(recoveryCodeService.generate(USER_ID, "000000")).thenThrow(new BizException(ErrorCode.SMS_CODE_INVALID));
        mvc.perform(auth(json(post("/pestai/v1/user/recovery-code"), "{\"code\":\"000000\"}")))
                .andExpect(jsonPath("$.code").value(40103));
    }

    // ------------------------------------------------------------------ 更换手机号、注销账号

    @Test
    void changePhoneSmsValidatesPhoneAndUsesUserFromToken() throws Exception {
        mvc.perform(auth(json(post("/pestai/v1/user/phone/sms"), "{\"new_phone\":\"1390000111\"}")))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(Rules.PHONE_MESSAGE));
        mvc.perform(auth(json(post("/pestai/v1/user/phone/sms"), "{}")))
                .andExpect(jsonPath("$.code").value(40001));
        verifyNoInteractions(accountService);

        // 请求体里的 user_id 不起作用：用户只取自令牌
        mvc.perform(auth(json(post("/pestai/v1/user/phone/sms"), "{\"new_phone\":\"13900001111\",\"user_id\":999}")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(accountService).sendChangePhoneCode(USER_ID, NEW_PHONE);
    }

    @Test
    void changePhoneSmsBusinessErrorsKeepCodeAndMessage() throws Exception {
        doThrow(BizException.param(AccountService.SAME_PHONE))
                .when(accountService).sendChangePhoneCode(USER_ID, "13800005678");
        mvc.perform(auth(json(post("/pestai/v1/user/phone/sms"), "{\"new_phone\":\"13800005678\"}")))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("新手机号不能与当前手机号相同"));

        doThrow(new BizException(ErrorCode.PHONE_REGISTERED, AccountService.PHONE_TAKEN))
                .when(accountService).sendChangePhoneCode(USER_ID, NEW_PHONE);
        mvc.perform(auth(json(post("/pestai/v1/user/phone/sms"), "{\"new_phone\":\"13900001111\"}")))
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.message").value("该手机号已注册，请先注销该账号或更换其他号码"));

        doThrow(new BizException(ErrorCode.TOO_FREQUENT)).when(accountService).sendChangePhoneCode(USER_ID, NEW_PHONE);
        mvc.perform(auth(json(post("/pestai/v1/user/phone/sms"), "{\"new_phone\":\"13900001111\"}")))
                .andExpect(jsonPath("$.code").value(42901));
    }

    @Test
    void changePhoneReturnsLoginVoWithNewToken() throws Exception {
        UserVO user = new UserVO(USER_ID, NEW_PHONE, "老李种菜", null, "江苏省 南京市 江宁区", "种植户", null);
        when(accountService.changePhone(USER_ID, NEW_PHONE, "123456")).thenReturn(new LoginVO("new-token", user, false));

        mvc.perform(auth(json(put("/pestai/v1/user/phone"), "{\"new_phone\":\"13900001111\",\"code\":\"123456\"}")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("new-token"))
                .andExpect(jsonPath("$.data.user.phone").value(NEW_PHONE))
                .andExpect(jsonPath("$.data.is_new").value(false));

        mvc.perform(auth(json(put("/pestai/v1/user/phone"), "{\"new_phone\":\"13900001111\",\"code\":\"12345\"}")))
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(Rules.SMS_CODE_MESSAGE));

        when(accountService.changePhone(USER_ID, NEW_PHONE, "000000"))
                .thenThrow(new BizException(ErrorCode.SMS_CODE_INVALID));
        mvc.perform(auth(json(put("/pestai/v1/user/phone"), "{\"new_phone\":\"13900001111\",\"code\":\"000000\"}")))
                .andExpect(jsonPath("$.code").value(40103));
    }

    @Test
    void deactivateSmsAcceptsEmptyOrMissingBody() throws Exception {
        mvc.perform(auth(json(post("/pestai/v1/user/deactivate/sms"), "{}")))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(auth(post("/pestai/v1/user/deactivate/sms")))
                .andExpect(jsonPath("$.code").value(0));
        verify(accountService, times(2)).sendDeactivateCode(USER_ID);
    }

    @Test
    void deactivateValidatesCodeAndCallsService() throws Exception {
        mvc.perform(auth(json(post("/pestai/v1/user/deactivate"), "{\"code\":\"abc\"}")))
                .andExpect(jsonPath("$.code").value(40001));
        verify(accountService, never()).deactivate(anyLong(), anyString());

        mvc.perform(auth(json(post("/pestai/v1/user/deactivate"), "{\"code\":\"123456\"}")))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(accountService).deactivate(USER_ID, "123456");

        doThrow(new BizException(ErrorCode.SMS_CODE_INVALID)).when(accountService).deactivate(USER_ID, "000000");
        mvc.perform(auth(json(post("/pestai/v1/user/deactivate"), "{\"code\":\"000000\"}")))
                .andExpect(jsonPath("$.code").value(40103));
    }

    @Test
    void tokenOfDeactivatedUserIs40101() throws Exception {
        // 注销后 t_user 中已没有该用户
        when(userMapper.selectAuthInfo(USER_ID)).thenReturn(null);
        mvc.perform(get("/pestai/v1/user/me").header("Authorization", bearer))
                .andExpect(jsonPath("$.code").value(40101));
        mvc.perform(auth(json(post("/pestai/v1/user/deactivate"), "{\"code\":\"123456\"}")))
                .andExpect(jsonPath("$.code").value(40101));
        mvc.perform(auth(get("/pestai/v1/user/recovery-code")))
                .andExpect(jsonPath("$.code").value(40101));
        verifyNoInteractions(accountService, recoveryCodeService);
        verify(recoverService, never()).rebind(anyString(), anyString(), anyString(), anyString(), isNull());
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", bearer);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
