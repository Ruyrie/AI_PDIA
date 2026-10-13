package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.RecoverMethod;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.ResetTicketMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.security.JwtTokenProvider;
import com.heban.pestai.server.vo.RebindTicketVO;
import com.heban.pestai.server.vo.RecoveryCodeVO;

class RecoveryCodeServiceTest {

    private static final long USER_ID = 10001L;
    private static final String PHONE = "13800005678";
    private static final String CODE = "048215";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 带毫秒：生成时间应截到秒（列为 DATETIME） */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 9, 30, 15, 678_000_000);
    private static final LocalDateTime NOW_SECONDS = LocalDateTime.of(2026, 10, 13, 9, 30, 15);

    @TempDir
    Path tempDir;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private Clock clock;
    private UserMapper userMapper;
    private ResetTicketMapper ticketMapper;
    private SmsService smsService;
    private RecoveryCodeService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        userMapper = mock(UserMapper.class);
        ticketMapper = mock(ResetTicketMapper.class);
        smsService = mock(SmsService.class);
        service = new RecoveryCodeService(userMapper, ticketMapper, smsService, encoder, clock);
        when(userMapper.selectById(USER_ID)).thenReturn(user(null, null));
        when(userMapper.updateRecoveryCode(eq(USER_ID), anyString(), any())).thenReturn(1);
    }

    @Test
    void statusWhenNotGenerated() {
        RecoveryCodeVO vo = service.status(USER_ID);
        assertThat(vo.generated()).isFalse();
        assertThat(vo.createdAt()).isNull();
        assertThat(vo.recoveryCode()).isNull();
        verifyNoInteractions(smsService, ticketMapper);
    }

    @Test
    void statusWhenGeneratedNeverReturnsPlainText() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 1, 8, 0, 0);
        when(userMapper.selectById(USER_ID)).thenReturn(user(encoder.encode("ABCDEFGHJKLM"), createdAt));

        RecoveryCodeVO vo = service.status(USER_ID);
        assertThat(vo.generated()).isTrue();
        assertThat(vo.createdAt()).isEqualTo(createdAt);
        assertThat(vo.recoveryCode()).isNull();
    }

    @Test
    void missingOrDisabledUserIs40101() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);
        assertBiz(() -> service.status(USER_ID), ErrorCode.TOKEN_INVALID);
        assertBiz(() -> service.sendCode(USER_ID), ErrorCode.TOKEN_INVALID);

        User disabled = user(null, null);
        disabled.setStatus(User.STATUS_DISABLED);
        when(userMapper.selectById(USER_ID)).thenReturn(disabled);
        assertBiz(() -> service.generate(USER_ID, CODE), ErrorCode.TOKEN_INVALID);
        verifyNoInteractions(smsService);
    }

    @Test
    void smsGoesToCurrentPhoneWithRecoveryScene() {
        service.sendCode(USER_ID);
        verify(smsService).send(PHONE, SmsScene.RECOVERY);

        doThrow(new BizException(ErrorCode.TOO_FREQUENT)).when(smsService).send(PHONE, SmsScene.RECOVERY);
        assertBiz(() -> service.sendCode(USER_ID), ErrorCode.TOO_FREQUENT);
    }

    @Test
    void generateConsumesCodeStoresOnlyBcryptHashAndReturnsFormattedCodeOnce() {
        RecoveryCodeVO vo = service.generate(USER_ID, CODE);

        assertThat(vo.generated()).isTrue();
        assertThat(vo.createdAt()).isEqualTo(NOW_SECONDS);
        assertThat(vo.recoveryCode()).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}(-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}){2}");

        InOrder order = inOrder(smsService, userMapper, ticketMapper);
        order.verify(smsService).verifyAndConsume(PHONE, SmsScene.RECOVERY, CODE);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        order.verify(userMapper).updateRecoveryCode(eq(USER_ID), hash.capture(), eq(NOW_SECONDS));
        // 旧恢复码签发的找回账号凭证一并作废
        order.verify(ticketMapper).invalidateByUser(USER_ID, NOW_SECONDS);

        String plain = RecoveryCodes.normalize(vo.recoveryCode());
        assertThat(hash.getValue()).startsWith("$2a$").doesNotContain(plain);
        assertThat(encoder.matches(plain, hash.getValue())).isTrue();
        // 明文只出现在这次响应中：之后查询状态不再返回
        when(userMapper.selectById(USER_ID)).thenReturn(user(hash.getValue(), NOW_SECONDS));
        assertThat(service.status(USER_ID).recoveryCode()).isNull();
    }

    @Test
    void generateWithWrongCodeIs40103AndChangesNothing() {
        doThrow(new BizException(ErrorCode.SMS_CODE_INVALID)).when(smsService)
                .verifyAndConsume(PHONE, SmsScene.RECOVERY, "000000");

        assertBiz(() -> service.generate(USER_ID, "000000"), ErrorCode.SMS_CODE_INVALID);
        verify(userMapper, never()).updateRecoveryCode(anyLong(), anyString(), any());
        verifyNoInteractions(ticketMapper);
    }

    @Test
    void generateWhenUserRowGoneIs40101() {
        when(userMapper.updateRecoveryCode(eq(USER_ID), anyString(), any())).thenReturn(0);
        assertBiz(() -> service.generate(USER_ID, CODE), ErrorCode.TOKEN_INVALID);
        verifyNoInteractions(ticketMapper);
    }

    @Test
    void regenerateMakesOldCodeInvalidImmediately() {
        String first = service.generate(USER_ID, CODE).recoveryCode();
        String second = service.generate(USER_ID, "135790").recoveryCode();
        assertThat(second).isNotEqualTo(first);

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(userMapper, times(2)).updateRecoveryCode(eq(USER_ID), hashes.capture(), eq(NOW_SECONDS));
        List<String> stored = hashes.getAllValues();
        String current = stored.get(1);
        assertThat(encoder.matches(RecoveryCodes.normalize(second), current)).isTrue();
        assertThat(encoder.matches(RecoveryCodes.normalize(first), current)).isFalse();
        verify(ticketMapper, times(2)).invalidateByUser(USER_ID, NOW_SECONDS);

        // 用找回账号验证：库中已是新哈希，旧恢复码返回 40105，新恢复码通过
        User withNewCode = user(current, NOW_SECONDS);
        withNewCode.setPasswordHash(encoder.encode("abc12345"));
        when(userMapper.selectByPhone(PHONE)).thenReturn(withNewCode);
        JwtTokenProvider tokens = new JwtTokenProvider("unit-test-secret-0123456789-abcdefghijklmnop",
                Duration.ofDays(30), clock);
        RecoverService recover = new RecoverService(userMapper, ticketMapper, smsService, encoder, tokens,
                new UserAssembler(new FileStorageService(tempDir, "http://10.0.2.2:8080", clock)), clock);
        assertBiz(() -> recover.verify(PHONE, RecoverMethod.RECOVERY_CODE, null, first),
                ErrorCode.RECOVERY_CODE_INVALID);
        RebindTicketVO ok = recover.verify(PHONE, RecoverMethod.RECOVERY_CODE, null, second.toLowerCase());
        assertThat(ok.method()).isEqualTo("RECOVERY_CODE");
    }

    // ------------------------------------------------------------------ 刚验证过短信 10 分钟内免再次验证

    @Test
    void statusReportsVerifyRequiredByLastSmsVerification() {
        assertThat(service.status(USER_ID).verifyRequired()).isTrue();

        User justVerified = user(null, null);
        justVerified.setSmsVerifiedAt(NOW.minusMinutes(9));
        when(userMapper.selectById(USER_ID)).thenReturn(justVerified);
        assertThat(service.status(USER_ID).verifyRequired()).isFalse();

        User expired = user(null, null);
        expired.setSmsVerifiedAt(NOW.minusMinutes(11));
        when(userMapper.selectById(USER_ID)).thenReturn(expired);
        assertThat(service.status(USER_ID).verifyRequired()).isTrue();
    }

    @Test
    void generateWithoutCodeWithinWindowSkipsSms() {
        User justVerified = user(null, null);
        justVerified.setSmsVerifiedAt(NOW.minusMinutes(2));
        when(userMapper.selectById(USER_ID)).thenReturn(justVerified);

        RecoveryCodeVO vo = service.generate(USER_ID, null);

        assertThat(vo.recoveryCode()).matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");
        assertThat(vo.verifyRequired()).isFalse();
        verifyNoInteractions(smsService);
        verify(userMapper).updateRecoveryCode(eq(USER_ID), anyString(), eq(NOW_SECONDS));
    }

    @Test
    void generateWithoutCodeOutsideWindowIs40001AndChangesNothing() {
        User expired = user(null, null);
        expired.setSmsVerifiedAt(NOW.minusMinutes(11));
        when(userMapper.selectById(USER_ID)).thenReturn(expired);

        assertThatThrownBy(() -> service.generate(USER_ID, "  "))
                .isInstanceOf(BizException.class)
                .hasMessage(RecoveryCodeService.VERIFY_FIRST);
        when(userMapper.selectById(USER_ID)).thenReturn(user(null, null));
        assertBiz(() -> service.generate(USER_ID, null), ErrorCode.PARAM_ERROR);
        verify(userMapper, never()).updateRecoveryCode(anyLong(), anyString(), any());
        verifyNoInteractions(smsService, ticketMapper);
    }

    private static void assertBiz(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(code);
    }

    private static User user(String recoveryHash, LocalDateTime createdAt) {
        User u = new User();
        u.setId(USER_ID);
        u.setPhone(PHONE);
        u.setNickname("老李种菜");
        u.setRecoveryCodeHash(recoveryHash);
        u.setRecoveryCodeCreatedAt(createdAt);
        u.setStatus(User.STATUS_NORMAL);
        u.setTokenVersion(2);
        return u;
    }
}
