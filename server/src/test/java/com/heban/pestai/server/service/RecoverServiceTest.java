package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.RecoverMethod;
import com.heban.pestai.server.common.Rules;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.common.TicketPurpose;
import com.heban.pestai.server.entity.ResetTicket;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.ResetTicketMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.security.JwtTokenProvider;
import com.heban.pestai.server.vo.LoginVO;
import com.heban.pestai.server.vo.RebindTicketVO;

class RecoverServiceTest {

    private static final long USER_ID = 10001L;
    private static final String OLD_PHONE = "13800005678";
    private static final String NEW_PHONE = "13900001111";
    private static final String CODE = "048215";
    private static final String PASSWORD = "abc12345";
    private static final String RECOVERY_CODE = "ABCDEFGHJKLM";
    private static final String TICKET = "0123456789abcdef0123456789abcdef";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 9, 0, 0);

    @TempDir
    Path tempDir;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final String passwordHash = encoder.encode(PASSWORD);
    private final String recoveryHash = encoder.encode(RECOVERY_CODE);
    private UserMapper userMapper;
    private ResetTicketMapper ticketMapper;
    private SmsService smsService;
    private JwtTokenProvider tokens;
    private RecoverService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        userMapper = mock(UserMapper.class);
        ticketMapper = mock(ResetTicketMapper.class);
        smsService = mock(SmsService.class);
        tokens = new JwtTokenProvider("unit-test-secret-0123456789-abcdefghijklmnop", Duration.ofDays(30), clock);
        UserAssembler assembler = new UserAssembler(new FileStorageService(tempDir, "http://10.0.2.2:8080", clock));
        service = new RecoverService(userMapper, ticketMapper, smsService, encoder, tokens, assembler, clock);
    }

    // ================================================================== auth/recover/verify

    @Test
    void verifyByPasswordIssuesTicketAndStoresOnlyItsHash() {
        when(userMapper.selectByPhone(OLD_PHONE)).thenReturn(user(OLD_PHONE, passwordHash, null, 2));

        RebindTicketVO vo = service.verify(OLD_PHONE, RecoverMethod.PASSWORD, PASSWORD, null);

        assertThat(vo.rebindTicket()).matches("[0-9a-f]{32}");
        assertThat(vo.expireIn()).isEqualTo(600);
        assertThat(vo.method()).isEqualTo("PASSWORD");
        InOrder order = inOrder(ticketMapper);
        order.verify(ticketMapper).invalidateByUser(USER_ID, NOW);
        ArgumentCaptor<ResetTicket> saved = ArgumentCaptor.forClass(ResetTicket.class);
        order.verify(ticketMapper).insert(saved.capture());
        ResetTicket ticket = saved.getValue();
        assertThat(ticket.getTicketHash()).isEqualTo(PasswordResetService.sha256Hex(vo.rebindTicket()))
                .isNotEqualTo(vo.rebindTicket());
        assertThat(ticket.getPurpose()).isEqualTo(TicketPurpose.REBIND_PASSWORD);
        assertThat(ticket.getUserId()).isEqualTo(USER_ID);
        assertThat(ticket.getPhone()).isEqualTo(OLD_PHONE);
        assertThat(ticket.getExpireAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(ticket.getUsed()).isZero();
        // 验证身份不发短信、不改账号
        verifyNoInteractions(smsService);
        verify(userMapper, never()).updateForRebind(anyLong(), anyString(), any(), anyBoolean(), any());
    }

    @Test
    void verifyByRecoveryCodeIgnoresCaseSpacesAndHyphensAndKeepsCodeUntilRebind() {
        when(userMapper.selectByPhone(OLD_PHONE)).thenReturn(user(OLD_PHONE, passwordHash, recoveryHash, 2));

        for (String input : new String[] {"ABCD-EFGH-JKLM", "abcd-efgh-jklm", " abcd efgh jklm ", "ABCDEFGHJKLM",
                "aBcD-eFgH JkLm"}) {
            RebindTicketVO vo = service.verify(OLD_PHONE, RecoverMethod.RECOVERY_CODE, null, input);
            assertThat(vo.method()).as(input).isEqualTo("RECOVERY_CODE");
        }
        ArgumentCaptor<ResetTicket> saved = ArgumentCaptor.forClass(ResetTicket.class);
        verify(ticketMapper, org.mockito.Mockito.times(5)).insert(saved.capture());
        assertThat(saved.getAllValues()).extracting(ResetTicket::getPurpose).containsOnly(TicketPurpose.REBIND_RECOVERY);
        // 验证身份时恢复码不失效，换绑成功后才清空
        verify(userMapper, never()).updateForRebind(anyLong(), anyString(), any(), anyBoolean(), any());
        verify(userMapper, never()).updateRecoveryCode(anyLong(), any(), any());
    }

    @Test
    void verifyUnknownPhoneIs40104ForBothMethods() {
        when(userMapper.selectByPhone(OLD_PHONE)).thenReturn(null);
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.PASSWORD, PASSWORD, null),
                ErrorCode.NOT_REGISTERED, "该手机号未注册");
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.RECOVERY_CODE, null, "ABCD-EFGH-JKLM"),
                ErrorCode.NOT_REGISTERED, null);
        verifyNoInteractions(ticketMapper);
    }

    @Test
    void verifyWrongOrMissingPasswordIs40102() {
        when(userMapper.selectByPhone(OLD_PHONE)).thenReturn(user(OLD_PHONE, passwordHash, recoveryHash, 2));
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.PASSWORD, "wrong999", null),
                ErrorCode.WRONG_PASSWORD, "账号或密码错误");
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.PASSWORD, null, null), ErrorCode.WRONG_PASSWORD, null);
        // 密码方式不认恢复码
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.PASSWORD, RECOVERY_CODE, RECOVERY_CODE),
                ErrorCode.WRONG_PASSWORD, null);

        // 验证码注册、从未设置密码的账号
        when(userMapper.selectByPhone(OLD_PHONE)).thenReturn(user(OLD_PHONE, null, null, 2));
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.PASSWORD, PASSWORD, null), ErrorCode.WRONG_PASSWORD, null);
        verifyNoInteractions(ticketMapper);
    }

    @Test
    void verifyRecoveryCodeErrorsAre40105() {
        when(userMapper.selectByPhone(OLD_PHONE)).thenReturn(user(OLD_PHONE, passwordHash, recoveryHash, 2));
        // 格式正确但不一致
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.RECOVERY_CODE, null, "ABCD-EFGH-JKLN"),
                ErrorCode.RECOVERY_CODE_INVALID, "恢复码错误或已失效");
        // 格式不对（含 0、长度不足）、为空
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.RECOVERY_CODE, null, "ABCD-EFGH-JKL0"),
                ErrorCode.RECOVERY_CODE_INVALID, null);
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.RECOVERY_CODE, null, "ABCD-EFGH"),
                ErrorCode.RECOVERY_CODE_INVALID, null);
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.RECOVERY_CODE, null, null),
                ErrorCode.RECOVERY_CODE_INVALID, null);
        // 恢复码方式不认登录密码
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.RECOVERY_CODE, PASSWORD, PASSWORD),
                ErrorCode.RECOVERY_CODE_INVALID, null);

        // 从未生成，或已用于找回账号（已清空）：同样返回 40105
        when(userMapper.selectByPhone(OLD_PHONE)).thenReturn(user(OLD_PHONE, passwordHash, null, 2));
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.RECOVERY_CODE, null, "ABCD-EFGH-JKLM"),
                ErrorCode.RECOVERY_CODE_INVALID, null);
        verifyNoInteractions(ticketMapper);
    }

    @Test
    void verifyDisabledAccountIs40301AndMissingMethodIs40001() {
        User disabled = user(OLD_PHONE, passwordHash, recoveryHash, 2);
        disabled.setStatus(User.STATUS_DISABLED);
        when(userMapper.selectByPhone(OLD_PHONE)).thenReturn(disabled);
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.PASSWORD, PASSWORD, null),
                ErrorCode.ACCOUNT_DISABLED, "该账号已停用");
        assertBiz(() -> service.verify(OLD_PHONE, RecoverMethod.RECOVERY_CODE, null, "ABCD-EFGH-JKLM"),
                ErrorCode.ACCOUNT_DISABLED, null);

        assertBiz(() -> service.verify(OLD_PHONE, null, PASSWORD, null), ErrorCode.PARAM_ERROR, null);
        verifyNoInteractions(ticketMapper);
    }

    // ================================================================== auth/recover/rebind

    @Test
    void rebindByPasswordWithoutNewPasswordKeepsPasswordAndIssuesTokenForNewVersion() {
        givenTicket(ticket(TicketPurpose.REBIND_PASSWORD, NOW.plusMinutes(5), 0, OLD_PHONE));
        when(userMapper.selectById(USER_ID)).thenReturn(user(OLD_PHONE, passwordHash, recoveryHash, 2),
                user(NEW_PHONE, passwordHash, recoveryHash, 3));
        when(userMapper.updateForRebind(USER_ID, NEW_PHONE, null, false, NOW)).thenReturn(1);
        when(ticketMapper.markUsed(7L, NOW)).thenReturn(1);

        LoginVO vo = service.rebind(OLD_PHONE, TICKET, NEW_PHONE, CODE, null);

        InOrder order = inOrder(userMapper, smsService, ticketMapper);
        order.verify(userMapper).selectByPhone(NEW_PHONE);
        order.verify(smsService).verifyAndConsume(NEW_PHONE, SmsScene.REBIND, CODE);
        // 密码不变（passwordHash 为 null）、恢复码保留（clearRecoveryCode 为 false），token_version + 1、更新 last_login_at
        order.verify(userMapper).updateForRebind(USER_ID, NEW_PHONE, null, false, NOW);
        order.verify(ticketMapper).markUsed(7L, NOW);
        assertThat(vo.isNew()).isFalse();
        assertThat(vo.user().phone()).isEqualTo(NEW_PHONE);
        assertThat(vo.user().id()).isEqualTo(USER_ID);
        JwtTokenProvider.TokenClaims claims = tokens.parse(vo.token());
        assertThat(claims.userId()).isEqualTo(USER_ID);
        // 新令牌对应加 1 之后的版本号：本机直接登录，其他设备上的旧令牌（版本 2）失效
        assertThat(claims.tokenVersion()).isEqualTo(3);
    }

    @Test
    void rebindByPasswordWithNewPasswordUpdatesPassword() {
        givenTicket(ticket(TicketPurpose.REBIND_PASSWORD, NOW.plusMinutes(5), 0, OLD_PHONE));
        when(userMapper.selectById(USER_ID)).thenReturn(user(OLD_PHONE, passwordHash, null, 2),
                user(NEW_PHONE, "new-hash", null, 3));
        when(userMapper.updateForRebind(eq(USER_ID), eq(NEW_PHONE), anyString(), eq(false), eq(NOW))).thenReturn(1);
        when(ticketMapper.markUsed(7L, NOW)).thenReturn(1);

        service.rebind(OLD_PHONE, TICKET, NEW_PHONE, CODE, "newpass123");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(userMapper).updateForRebind(eq(USER_ID), eq(NEW_PHONE), hash.capture(), eq(false), eq(NOW));
        assertThat(hash.getValue()).startsWith("$2a$").isNotEqualTo("newpass123");
        assertThat(encoder.matches("newpass123", hash.getValue())).isTrue();
    }

    @Test
    void rebindByRecoveryCodeMustSetValidNewPassword() {
        givenTicket(ticket(TicketPurpose.REBIND_RECOVERY, NOW.plusMinutes(5), 0, OLD_PHONE));
        when(userMapper.selectById(USER_ID)).thenReturn(user(OLD_PHONE, passwordHash, recoveryHash, 2));

        assertBiz(() -> service.rebind(OLD_PHONE, TICKET, NEW_PHONE, CODE, null),
                ErrorCode.PARAM_ERROR, RecoverService.NEW_PASSWORD_REQUIRED);
        assertBiz(() -> service.rebind(OLD_PHONE, TICKET, NEW_PHONE, CODE, ""),
                ErrorCode.PARAM_ERROR, RecoverService.NEW_PASSWORD_REQUIRED);
        // 不合规：只有数字 / 太短
        assertBiz(() -> service.rebind(OLD_PHONE, TICKET, NEW_PHONE, CODE, "12345678"),
                ErrorCode.PARAM_ERROR, Rules.PASSWORD_MESSAGE);
        assertBiz(() -> service.rebind(OLD_PHONE, TICKET, NEW_PHONE, CODE, "abc1234"),
                ErrorCode.PARAM_ERROR, Rules.PASSWORD_MESSAGE);
        // 失败时验证码、凭证都不被消耗
        verifyNoInteractions(smsService);
        verify(userMapper, never()).updateForRebind(anyLong(), anyString(), any(), anyBoolean(), any());
        verify(ticketMapper, never()).markUsed(anyLong(), any());
    }

    @Test
    void rebindByRecoveryCodeSetsPasswordAndClearsRecoveryCode() {
        givenTicket(ticket(TicketPurpose.REBIND_RECOVERY, NOW.plusMinutes(5), 0, OLD_PHONE));
        when(userMapper.selectById(USER_ID)).thenReturn(user(OLD_PHONE, passwordHash, recoveryHash, 4),
                user(NEW_PHONE, "new-hash", null, 5));
        when(userMapper.updateForRebind(eq(USER_ID), eq(NEW_PHONE), anyString(), eq(true), eq(NOW))).thenReturn(1);
        when(ticketMapper.markUsed(7L, NOW)).thenReturn(1);

        LoginVO vo = service.rebind(OLD_PHONE, TICKET, NEW_PHONE, CODE, "newpass123");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(userMapper).updateForRebind(eq(USER_ID), eq(NEW_PHONE), hash.capture(), eq(true), eq(NOW));
        assertThat(encoder.matches("newpass123", hash.getValue())).isTrue();
        verify(smsService).verifyAndConsume(NEW_PHONE, SmsScene.REBIND, CODE);
        assertThat(tokens.parse(vo.token()).tokenVersion()).isEqualTo(5);
        assertThat(vo.user().phone()).isEqualTo(NEW_PHONE);
    }

    @Test
    void rebindRejectsMissingExpiredUsedForeignOrWrongPurposeTickets() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(OLD_PHONE, passwordHash, recoveryHash, 2));

        // 不存在
        givenTicket(null);
        assertTicketExpired("newpass123");
        // 已过期（恰好到期也算过期）
        givenTicket(ticket(TicketPurpose.REBIND_PASSWORD, NOW.minusSeconds(1), 0, OLD_PHONE));
        assertTicketExpired(null);
        givenTicket(ticket(TicketPurpose.REBIND_PASSWORD, NOW, 0, OLD_PHONE));
        assertTicketExpired(null);
        // 已使用 / 已作废
        givenTicket(ticket(TicketPurpose.REBIND_RECOVERY, NOW.plusMinutes(5), 1, OLD_PHONE));
        assertTicketExpired("newpass123");
        // 别人的：凭证签发给另一个原手机号
        givenTicket(ticket(TicketPurpose.REBIND_PASSWORD, NOW.plusMinutes(5), 0, "13700001234"));
        assertTicketExpired(null);
        // 用途不对：找回密码的 reset_ticket 不能用来换绑
        givenTicket(ticket(TicketPurpose.RESET, NOW.plusMinutes(5), 0, OLD_PHONE));
        assertTicketExpired("newpass123");
        givenTicket(ticket(null, NOW.plusMinutes(5), 0, OLD_PHONE));
        assertTicketExpired("newpass123");
        // 明文为空
        assertBiz(() -> service.rebind(OLD_PHONE, null, NEW_PHONE, CODE, null),
                ErrorCode.PARAM_ERROR, RecoverService.TICKET_EXPIRED);

        verifyNoInteractions(smsService);
        verify(userMapper, never()).updateForRebind(anyLong(), anyString(), any(), anyBoolean(), any());
        verify(ticketMapper, never()).markUsed(anyLong(), any());
    }

    @Test
    void rebindRejectsTicketWhenAccountChangedAfterVerify() {
        givenTicket(ticket(TicketPurpose.REBIND_RECOVERY, NOW.plusMinutes(5), 0, OLD_PHONE));
        // 账号已注销
        when(userMapper.selectById(USER_ID)).thenReturn(null);
        assertTicketExpired("newpass123");
        // 账号已换绑 / 更换手机号：凭证上的原手机号不再属于该用户
        when(userMapper.selectById(USER_ID)).thenReturn(user("13700001234", passwordHash, recoveryHash, 3));
        assertTicketExpired("newpass123");
        // 恢复码已被用掉（清空）
        when(userMapper.selectById(USER_ID)).thenReturn(user(OLD_PHONE, passwordHash, null, 3));
        assertTicketExpired("newpass123");

        verifyNoInteractions(smsService);
        verify(userMapper, never()).updateForRebind(anyLong(), anyString(), any(), anyBoolean(), any());
    }

    @Test
    void rebindRejectsSamePhoneAndRegisteredNewPhoneBeforeConsumingCode() {
        givenTicket(ticket(TicketPurpose.REBIND_PASSWORD, NOW.plusMinutes(5), 0, OLD_PHONE));
        when(userMapper.selectById(USER_ID)).thenReturn(user(OLD_PHONE, passwordHash, null, 2));

        assertBiz(() -> service.rebind(OLD_PHONE, TICKET, OLD_PHONE, CODE, null),
                ErrorCode.PARAM_ERROR, "新手机号不能与原手机号相同");

        User other = user(NEW_PHONE, null, null, 0);
        other.setId(20002L);
        when(userMapper.selectByPhone(NEW_PHONE)).thenReturn(other);
        assertBiz(() -> service.rebind(OLD_PHONE, TICKET, NEW_PHONE, CODE, null),
                ErrorCode.PHONE_REGISTERED, AccountService.PHONE_TAKEN);

        verifyNoInteractions(smsService);
        verify(userMapper, never()).updateForRebind(anyLong(), anyString(), any(), anyBoolean(), any());
        verify(ticketMapper, never()).markUsed(anyLong(), any());
    }

    @Test
    void rebindWithWrongSmsCodeIs40103AndChangesNothing() {
        givenTicket(ticket(TicketPurpose.REBIND_PASSWORD, NOW.plusMinutes(5), 0, OLD_PHONE));
        when(userMapper.selectById(USER_ID)).thenReturn(user(OLD_PHONE, passwordHash, null, 2));
        doThrow(new BizException(ErrorCode.SMS_CODE_INVALID)).when(smsService)
                .verifyAndConsume(NEW_PHONE, SmsScene.REBIND, "000000");

        assertBiz(() -> service.rebind(OLD_PHONE, TICKET, NEW_PHONE, "000000", null), ErrorCode.SMS_CODE_INVALID, null);
        verify(userMapper, never()).updateForRebind(anyLong(), anyString(), any(), anyBoolean(), any());
        verify(ticketMapper, never()).markUsed(anyLong(), any());
    }

    @Test
    void rebindLosingRaceOnUniqueIndexIs40901AndConcurrentTicketUseFails() {
        givenTicket(ticket(TicketPurpose.REBIND_PASSWORD, NOW.plusMinutes(5), 0, OLD_PHONE));
        when(userMapper.selectById(USER_ID)).thenReturn(user(OLD_PHONE, passwordHash, null, 2));
        when(userMapper.updateForRebind(USER_ID, NEW_PHONE, null, false, NOW))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_phone'"));
        assertBiz(() -> service.rebind(OLD_PHONE, TICKET, NEW_PHONE, CODE, null),
                ErrorCode.PHONE_REGISTERED, AccountService.PHONE_TAKEN);
        verify(ticketMapper, never()).markUsed(anyLong(), any());

        // 凭证已被并发请求用掉：抛出异常，换绑随事务回滚
        org.mockito.Mockito.reset(userMapper);
        when(userMapper.selectById(USER_ID)).thenReturn(user(OLD_PHONE, passwordHash, null, 2),
                user(NEW_PHONE, passwordHash, null, 3));
        when(userMapper.updateForRebind(USER_ID, NEW_PHONE, null, false, NOW)).thenReturn(1);
        when(ticketMapper.markUsed(7L, NOW)).thenReturn(0);
        assertBiz(() -> service.rebind(OLD_PHONE, TICKET, NEW_PHONE, CODE, null),
                ErrorCode.PARAM_ERROR, RecoverService.TICKET_EXPIRED);
    }

    @Test
    void rebindDisabledAccountIs40301() {
        givenTicket(ticket(TicketPurpose.REBIND_PASSWORD, NOW.plusMinutes(5), 0, OLD_PHONE));
        User disabled = user(OLD_PHONE, passwordHash, null, 2);
        disabled.setStatus(User.STATUS_DISABLED);
        when(userMapper.selectById(USER_ID)).thenReturn(disabled);

        assertBiz(() -> service.rebind(OLD_PHONE, TICKET, NEW_PHONE, CODE, null), ErrorCode.ACCOUNT_DISABLED, null);
        verifyNoInteractions(smsService);
    }

    @Test
    void ticketFromVerifyWorksOnceForRebind() {
        when(userMapper.selectByPhone(OLD_PHONE)).thenReturn(user(OLD_PHONE, passwordHash, recoveryHash, 2));
        RebindTicketVO issued = service.verify(OLD_PHONE, RecoverMethod.RECOVERY_CODE, null, "abcd-efgh-jklm");
        ArgumentCaptor<ResetTicket> saved = ArgumentCaptor.forClass(ResetTicket.class);
        verify(ticketMapper).insert(saved.capture());
        ResetTicket stored = saved.getValue();
        stored.setId(7L);
        // 库中只有摘要：按明文的摘要能查到这张凭证
        when(ticketMapper.selectByHash(PasswordResetService.sha256Hex(issued.rebindTicket()))).thenReturn(stored);
        when(userMapper.selectByPhone(OLD_PHONE)).thenReturn(null);
        when(userMapper.selectById(USER_ID)).thenReturn(user(OLD_PHONE, passwordHash, recoveryHash, 2),
                user(NEW_PHONE, "new-hash", null, 3));
        when(userMapper.updateForRebind(eq(USER_ID), eq(NEW_PHONE), anyString(), eq(true), eq(NOW))).thenReturn(1);
        when(ticketMapper.markUsed(7L, NOW)).thenReturn(1);

        LoginVO vo = service.rebind(OLD_PHONE, issued.rebindTicket(), NEW_PHONE, CODE, "newpass123");
        assertThat(tokens.parse(vo.token()).tokenVersion()).isEqualTo(3);

        // 第二次使用：凭证已标记为已用
        stored.setUsed(1);
        when(userMapper.selectById(USER_ID)).thenReturn(user(NEW_PHONE, "new-hash", null, 3));
        assertBiz(() -> service.rebind(OLD_PHONE, issued.rebindTicket(), NEW_PHONE, CODE, "newpass123"),
                ErrorCode.PARAM_ERROR, RecoverService.TICKET_EXPIRED);
        verify(userMapper).updateForRebind(eq(USER_ID), eq(NEW_PHONE), anyString(), eq(true), eq(NOW));
        verify(userMapper, never()).updateForRebind(anyLong(), anyString(), isNull(), anyBoolean(), any());
    }

    // ------------------------------------------------------------------

    private void assertTicketExpired(String newPassword) {
        assertBiz(() -> service.rebind(OLD_PHONE, TICKET, NEW_PHONE, CODE, newPassword),
                ErrorCode.PARAM_ERROR, "验证已过期，请重新验证身份");
    }

    private void givenTicket(ResetTicket ticket) {
        when(ticketMapper.selectByHash(PasswordResetService.sha256Hex(TICKET))).thenReturn(ticket);
    }

    private static void assertBiz(ThrowingCallable call, ErrorCode code, String message) {
        assertThatThrownBy(call).isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(code);
                    if (message != null) {
                        assertThat(e.getMessage()).isEqualTo(message);
                    }
                });
    }

    private static ResetTicket ticket(String purpose, LocalDateTime expireAt, int used, String phone) {
        ResetTicket t = new ResetTicket();
        t.setId(7L);
        t.setUserId(USER_ID);
        t.setPhone(phone);
        t.setTicketHash(PasswordResetService.sha256Hex(TICKET));
        t.setPurpose(purpose);
        t.setExpireAt(expireAt);
        t.setUsed(used);
        return t;
    }

    private static User user(String phone, String passwordHash, String recoveryHash, int tokenVersion) {
        User u = new User();
        u.setId(USER_ID);
        u.setPhone(phone);
        u.setPasswordHash(passwordHash);
        u.setRecoveryCodeHash(recoveryHash);
        u.setRecoveryCodeCreatedAt(recoveryHash == null ? null : NOW.minusDays(3));
        u.setNickname("老李种菜");
        u.setRegion("江苏省 南京市 江宁区");
        u.setIdentity("种植户");
        u.setStatus(User.STATUS_NORMAL);
        u.setTokenVersion(tokenVersion);
        return u;
    }
}
