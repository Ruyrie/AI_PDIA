package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.heban.pestai.server.client.SmsClient;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.entity.SmsCode;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.SmsCodeMapper;
import com.heban.pestai.server.mapper.UserMapper;

class SmsServiceTest {

    private static final String PHONE = "13800005678";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 14, 20, 0);

    private SmsCodeMapper smsCodeMapper;
    private UserMapper userMapper;
    private SmsClient smsClient;
    private SmsService service;

    @BeforeEach
    void setUp() {
        smsCodeMapper = mock(SmsCodeMapper.class);
        userMapper = mock(UserMapper.class);
        smsClient = mock(SmsClient.class);
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        service = new SmsService(smsCodeMapper, userMapper, smsClient, clock);
    }

    @Test
    void secondSendWithin60SecondsIsRejectedWith42901() throws Exception {
        when(smsCodeMapper.selectLatest(PHONE, "LOGIN")).thenReturn(code("123456", NOW.minusSeconds(30), 0));

        assertThatThrownBy(() -> service.send(PHONE, SmsScene.LOGIN))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TOO_FREQUENT.getCode());
        verify(smsCodeMapper, never()).insert(any());
        verify(smsClient, never()).sendCode(anyString(), any(), anyString());
    }

    @Test
    void sendAfter60SecondsCreatesSixDigitCodeValidFor5Minutes() throws Exception {
        when(smsCodeMapper.selectLatest(PHONE, "LOGIN")).thenReturn(code("123456", NOW.minusSeconds(61), 0));

        service.send(PHONE, SmsScene.LOGIN);

        ArgumentCaptor<SmsCode> captor = ArgumentCaptor.forClass(SmsCode.class);
        verify(smsCodeMapper).insert(captor.capture());
        SmsCode saved = captor.getValue();
        assertThat(saved.getCode()).matches("\\d{6}");
        assertThat(saved.getScene()).isEqualTo("LOGIN");
        assertThat(saved.getUsed()).isZero();
        assertThat(saved.getExpireAt()).isEqualTo(NOW.plusMinutes(5));
        verify(smsClient).sendCode(PHONE, SmsScene.LOGIN, saved.getCode());
    }

    @Test
    void rateLimitIsPerScene() throws Exception {
        when(smsCodeMapper.selectLatest(PHONE, "LOGIN")).thenReturn(code("123456", NOW.minusSeconds(5), 0));
        when(smsCodeMapper.selectLatest(PHONE, "RESET")).thenReturn(null);
        when(userMapper.selectByPhone(PHONE)).thenReturn(new User());

        service.send(PHONE, SmsScene.RESET);

        verify(smsClient).sendCode(eq(PHONE), eq(SmsScene.RESET), anyString());
    }

    @Test
    void resetSceneRequiresRegisteredPhone() {
        when(userMapper.selectByPhone(PHONE)).thenReturn(null);
        assertThatThrownBy(() -> service.send(PHONE, SmsScene.RESET))
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_REGISTERED.getCode());
    }

    @Test
    void rebindAndRegisterScenesRejectRegisteredPhone() throws Exception {
        when(userMapper.selectByPhone(PHONE)).thenReturn(new User());
        // 找回账号：验证码发往新手机号，新号已注册 40901
        assertThatThrownBy(() -> service.send(PHONE, SmsScene.REBIND))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.PHONE_REGISTERED);
                    assertThat(e.getMessage()).isEqualTo(AccountService.PHONE_TAKEN);
                });
        assertThatThrownBy(() -> service.send(PHONE, SmsScene.REGISTER))
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PHONE_REGISTERED.getCode());
        verify(smsCodeMapper, never()).insert(any());
        verify(smsClient, never()).sendCode(anyString(), any(), anyString());
    }

    @Test
    void rebindSceneSendsToUnregisteredNewPhone() throws Exception {
        when(userMapper.selectByPhone(PHONE)).thenReturn(null);

        service.send(PHONE, SmsScene.REBIND);

        ArgumentCaptor<SmsCode> captor = ArgumentCaptor.forClass(SmsCode.class);
        verify(smsCodeMapper).insert(captor.capture());
        assertThat(captor.getValue().getScene()).isEqualTo("REBIND");
        verify(smsClient).sendCode(eq(PHONE), eq(SmsScene.REBIND), anyString());
    }

    @Test
    void changePhoneDeactivateAndRecoveryScenesAreSentAndRateLimitedPerScene() throws Exception {
        // 需要登录的场景，号码由 AccountService / RecoveryCodeService 决定并预先检查，这里不再查询注册状态
        service.send(PHONE, SmsScene.CHANGE_PHONE);
        service.send(PHONE, SmsScene.DEACTIVATE);
        service.send(PHONE, SmsScene.RECOVERY);

        ArgumentCaptor<SmsCode> captor = ArgumentCaptor.forClass(SmsCode.class);
        verify(smsCodeMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(SmsCode::getScene)
                .containsExactly("CHANGE_PHONE", "DEACTIVATE", "RECOVERY");
        verify(smsClient).sendCode(eq(PHONE), eq(SmsScene.CHANGE_PHONE), anyString());
        verify(smsClient).sendCode(eq(PHONE), eq(SmsScene.DEACTIVATE), anyString());
        verify(smsClient).sendCode(eq(PHONE), eq(SmsScene.RECOVERY), anyString());
        verify(userMapper, never()).selectByPhone(anyString());
        when(smsCodeMapper.selectLatest(PHONE, "RECOVERY")).thenReturn(code("123456", NOW.minusSeconds(20), 0));
        assertThatThrownBy(() -> service.send(PHONE, SmsScene.RECOVERY))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_FREQUENT);

        when(smsCodeMapper.selectLatest(PHONE, "CHANGE_PHONE")).thenReturn(code("123456", NOW.minusSeconds(59), 0));
        assertThatThrownBy(() -> service.send(PHONE, SmsScene.CHANGE_PHONE))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_FREQUENT);
        when(smsCodeMapper.selectLatest(PHONE, "DEACTIVATE")).thenReturn(code("123456", NOW.minusSeconds(10), 0));
        assertThatThrownBy(() -> service.send(PHONE, SmsScene.DEACTIVATE))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_FREQUENT);
    }

    @Test
    void smsClientFailureBecomes50001() throws Exception {
        doThrow(new SmsClient.SmsSendException("boom")).when(smsClient).sendCode(anyString(), any(), anyString());
        assertThatThrownBy(() -> service.send(PHONE, SmsScene.LOGIN))
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.SERVER_ERROR.getCode());
    }

    @Test
    void verifyAndConsumeSucceedsOnceForLatestValidCode() {
        SmsCode latest = code("048215", NOW.minusMinutes(1), 0);
        when(smsCodeMapper.selectLatest(PHONE, "LOGIN")).thenReturn(latest);
        when(smsCodeMapper.markUsed(eq(latest.getId()), any())).thenReturn(1);

        service.verifyAndConsume(PHONE, SmsScene.LOGIN, "048215");

        verify(smsCodeMapper).markUsed(eq(latest.getId()), eq(NOW));
    }

    @Test
    void verifyRejectsWrongExpiredUsedOrMissingCode() {
        when(smsCodeMapper.selectLatest(PHONE, "LOGIN")).thenReturn(code("048215", NOW.minusMinutes(1), 0));
        assertInvalid("111111");

        when(smsCodeMapper.selectLatest(PHONE, "LOGIN")).thenReturn(code("048215", NOW.minusMinutes(6), 0));
        assertInvalid("048215");

        when(smsCodeMapper.selectLatest(PHONE, "LOGIN")).thenReturn(code("048215", NOW.minusMinutes(1), 1));
        assertInvalid("048215");

        when(smsCodeMapper.selectLatest(PHONE, "LOGIN")).thenReturn(null);
        assertInvalid("048215");
    }

    @Test
    void concurrentConsumeLosesWhenMarkUsedAffectsNoRow() {
        when(smsCodeMapper.selectLatest(PHONE, "LOGIN")).thenReturn(code("048215", NOW.minusMinutes(1), 0));
        when(smsCodeMapper.markUsed(anyLong(), any())).thenReturn(0);
        assertInvalid("048215");
    }

    private void assertInvalid(String input) {
        assertThatThrownBy(() -> service.verifyAndConsume(PHONE, SmsScene.LOGIN, input))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.SMS_CODE_INVALID.getCode());
    }

    private static SmsCode code(String value, LocalDateTime createdAt, int used) {
        SmsCode c = new SmsCode();
        c.setId(99L);
        c.setPhone(PHONE);
        c.setCode(value);
        c.setCreatedAt(createdAt);
        c.setExpireAt(createdAt.plusMinutes(5));
        c.setUsed(used);
        return c;
    }
}
