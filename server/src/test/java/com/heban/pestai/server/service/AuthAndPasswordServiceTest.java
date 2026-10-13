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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.common.TicketPurpose;
import com.heban.pestai.server.entity.ResetTicket;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.ResetTicketMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.security.JwtTokenProvider;
import com.heban.pestai.server.vo.LoginVO;
import com.heban.pestai.server.vo.ResetTicketVO;

class AuthAndPasswordServiceTest {

    private static final String PHONE = "13800005678";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 9, 0, 0);

    @TempDir
    Path tempDir;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private UserMapper userMapper;
    private SmsService smsService;
    private ResetTicketMapper ticketMapper;
    private JwtTokenProvider tokens;
    private AuthService auth;
    private PasswordResetService reset;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        userMapper = mock(UserMapper.class);
        smsService = mock(SmsService.class);
        ticketMapper = mock(ResetTicketMapper.class);
        tokens = new JwtTokenProvider("unit-test-secret-0123456789-abcdefghijklmnop", Duration.ofDays(30), clock);
        UserAssembler assembler = new UserAssembler(new FileStorageService(tempDir, "http://10.0.2.2:8080", clock));
        auth = new AuthService(userMapper, smsService, encoder, tokens, assembler, clock);
        reset = new PasswordResetService(userMapper, ticketMapper, smsService, encoder, clock);
    }

    @Test
    void smsLoginAutoRegistersUnknownPhone() {
        when(userMapper.selectByPhone(PHONE)).thenReturn(null);
        when(userMapper.insert(any())).thenAnswer(inv -> {
            ((User) inv.getArgument(0)).setId(10001L);
            return 1;
        });

        LoginVO vo = auth.loginBySms(PHONE, "048215");

        verify(smsService).verifyAndConsume(PHONE, SmsScene.LOGIN, "048215");
        assertThat(vo.isNew()).isTrue();
        assertThat(vo.user().nickname()).isEqualTo("农友5678");
        assertThat(vo.user().identity()).isEqualTo("种植户");
        assertThat(vo.user().region()).isEmpty();
        assertThat(vo.user().avatarUrl()).isNull();
        assertThat(tokens.parse(vo.token()).userId()).isEqualTo(10001L);
        ArgumentCaptor<User> inserted = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getPasswordHash()).isNull();
        assertThat(inserted.getValue().getTokenVersion()).isZero();
        verify(userMapper).updateLastLogin(10001L, NOW);
    }

    @Test
    void smsLoginOfDisabledAccountIs40301() {
        User user = user(encoder.encode("abc12345"));
        user.setStatus(User.STATUS_DISABLED);
        when(userMapper.selectByPhone(PHONE)).thenReturn(user);
        assertThatThrownBy(() -> auth.loginBySms(PHONE, "048215"))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_DISABLED);
        // 40301 文案只说明账号已停用
        assertThatThrownBy(() -> auth.loginBySms(PHONE, "048215")).hasMessage("该账号已停用");
    }

    @Test
    void passwordLoginChecksRegistrationStatusAndPassword() {
        when(userMapper.selectByPhone(PHONE)).thenReturn(null);
        assertThatThrownBy(() -> auth.loginByPassword(PHONE, "abc12345"))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_REGISTERED);

        when(userMapper.selectByPhone(PHONE)).thenReturn(user(null));
        assertThatThrownBy(() -> auth.loginByPassword(PHONE, "abc12345"))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.WRONG_PASSWORD);

        when(userMapper.selectByPhone(PHONE)).thenReturn(user(encoder.encode("abc12345")));
        assertThatThrownBy(() -> auth.loginByPassword(PHONE, "wrong999"))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.WRONG_PASSWORD);

        LoginVO ok = auth.loginByPassword(PHONE, "abc12345");
        assertThat(ok.isNew()).isFalse();
        assertThat(tokens.parse(ok.token()).tokenVersion()).isEqualTo(2);
    }

    @Test
    void registerRejectsExistingPhoneBeforeConsumingCode() {
        when(userMapper.selectByPhone(PHONE)).thenReturn(user(null));
        assertThatThrownBy(() -> auth.register(PHONE, "048215", "abc12345"))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.PHONE_REGISTERED);
        verify(smsService, never()).verifyAndConsume(anyString(), any(), anyString());
    }

    @Test
    void registerStoresBcryptHash() {
        when(userMapper.selectByPhone(PHONE)).thenReturn(null);
        when(userMapper.insert(any())).thenAnswer(inv -> {
            ((User) inv.getArgument(0)).setId(10002L);
            return 1;
        });
        LoginVO vo = auth.register(PHONE, "048215", "abc12345");
        ArgumentCaptor<User> inserted = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getPasswordHash()).startsWith("$2a$");
        assertThat(encoder.matches("abc12345", inserted.getValue().getPasswordHash())).isTrue();
        assertThat(vo.isNew()).isFalse();
    }

    @Test
    void verifyIssuesHexTicketAndStoresOnlyItsHash() {
        when(userMapper.selectByPhone(PHONE)).thenReturn(user(null));

        ResetTicketVO vo = reset.verify(PHONE, "048215");

        assertThat(vo.resetTicket()).matches("[0-9a-f]{32}");
        assertThat(vo.expireIn()).isEqualTo(600);
        verify(ticketMapper).invalidateByUser(10001L, NOW);
        ArgumentCaptor<ResetTicket> saved = ArgumentCaptor.forClass(ResetTicket.class);
        verify(ticketMapper).insert(saved.capture());
        assertThat(saved.getValue().getTicketHash()).isEqualTo(PasswordResetService.sha256Hex(vo.resetTicket()));
        assertThat(saved.getValue().getTicketHash()).isNotEqualTo(vo.resetTicket());
        assertThat(saved.getValue().getExpireAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(saved.getValue().getPurpose()).isEqualTo(TicketPurpose.RESET);
    }

    @Test
    void resetRejectsSamePasswordWithoutBurningTicket() {
        String ticket = "0123456789abcdef0123456789abcdef";
        when(ticketMapper.selectByHash(PasswordResetService.sha256Hex(ticket))).thenReturn(ticket(ticket, NOW.plusMinutes(5)));
        when(userMapper.selectById(10001L)).thenReturn(user(encoder.encode("abc12345")));

        assertThatThrownBy(() -> reset.reset(PHONE, ticket, "abc12345"))
                .hasMessage(PasswordResetService.SAME_PASSWORD);
        verify(ticketMapper, never()).markUsed(anyLong(), any());
        verify(userMapper, never()).updatePasswordAndBumpVersion(anyLong(), anyString(), any());
    }

    @Test
    void resetRejectsExpiredUsedOrForeignTickets() {
        String ticket = "0123456789abcdef0123456789abcdef";
        String hash = PasswordResetService.sha256Hex(ticket);

        when(ticketMapper.selectByHash(hash)).thenReturn(ticket(ticket, NOW.minusSeconds(1)));
        assertThatThrownBy(() -> reset.reset(PHONE, ticket, "newpass123")).hasMessage(PasswordResetService.TICKET_EXPIRED);

        ResetTicket used = ticket(ticket, NOW.plusMinutes(5));
        used.setUsed(1);
        when(ticketMapper.selectByHash(hash)).thenReturn(used);
        assertThatThrownBy(() -> reset.reset(PHONE, ticket, "newpass123")).hasMessage(PasswordResetService.TICKET_EXPIRED);

        when(ticketMapper.selectByHash(hash)).thenReturn(ticket(ticket, NOW.plusMinutes(5)));
        assertThatThrownBy(() -> reset.reset("13900000000", ticket, "newpass123"))
                .hasMessage(PasswordResetService.TICKET_EXPIRED);
    }

    @Test
    void resetUpdatesPasswordBumpsVersionAndBurnsTicket() {
        String ticket = "0123456789abcdef0123456789abcdef";
        when(ticketMapper.selectByHash(PasswordResetService.sha256Hex(ticket))).thenReturn(ticket(ticket, NOW.plusMinutes(5)));
        when(userMapper.selectById(10001L)).thenReturn(user(encoder.encode("abc12345")));
        when(ticketMapper.markUsed(7L, NOW)).thenReturn(1);

        reset.reset(PHONE, ticket, "newpass123");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(userMapper).updatePasswordAndBumpVersion(eq(10001L), hash.capture(), eq(NOW));
        assertThat(encoder.matches("newpass123", hash.getValue())).isTrue();
        // 先核销本凭证，再作废该用户其他未使用的凭证（如用旧密码拿到的找回账号凭证）
        InOrder order = org.mockito.Mockito.inOrder(ticketMapper);
        order.verify(ticketMapper).markUsed(7L, NOW);
        order.verify(ticketMapper).invalidateByUser(10001L, NOW);
    }

    @Test
    void resetRejectsRebindTicketsAndUnknownPurpose() {
        String ticket = "0123456789abcdef0123456789abcdef";
        for (String purpose : new String[] {TicketPurpose.REBIND_PASSWORD, TicketPurpose.REBIND_RECOVERY, null}) {
            ResetTicket rebind = ticket(ticket, NOW.plusMinutes(5));
            rebind.setPurpose(purpose);
            when(ticketMapper.selectByHash(PasswordResetService.sha256Hex(ticket))).thenReturn(rebind);
            assertThatThrownBy(() -> reset.reset(PHONE, ticket, "newpass123"))
                    .hasMessage(PasswordResetService.TICKET_EXPIRED);
        }
        verify(userMapper, never()).updatePasswordAndBumpVersion(anyLong(), anyString(), any());
        verify(ticketMapper, never()).markUsed(anyLong(), any());
    }

    @Test
    void ticketAlreadyBurnedByConcurrentRequestFails() {
        String ticket = "0123456789abcdef0123456789abcdef";
        when(ticketMapper.selectByHash(PasswordResetService.sha256Hex(ticket))).thenReturn(ticket(ticket, NOW.plusMinutes(5)));
        when(userMapper.selectById(10001L)).thenReturn(user(null));
        when(ticketMapper.markUsed(7L, NOW)).thenReturn(0);
        assertThatThrownBy(() -> reset.reset(PHONE, ticket, "newpass123")).hasMessage(PasswordResetService.TICKET_EXPIRED);
    }

    @Test
    void verifyForUnknownPhoneIs40104AndBadCodePropagates() {
        when(userMapper.selectByPhone(PHONE)).thenReturn(null);
        assertThatThrownBy(() -> reset.verify(PHONE, "048215"))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_REGISTERED);

        when(userMapper.selectByPhone(PHONE)).thenReturn(user(null));
        doThrow(new BizException(ErrorCode.SMS_CODE_INVALID)).when(smsService)
                .verifyAndConsume(PHONE, SmsScene.RESET, "000000");
        assertThatThrownBy(() -> reset.verify(PHONE, "000000"))
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.SMS_CODE_INVALID);
        verify(ticketMapper, never()).insert(any());
    }

    private static User user(String hash) {
        User u = new User();
        u.setId(10001L);
        u.setPhone(PHONE);
        u.setPasswordHash(hash);
        u.setNickname("老李种菜");
        u.setRegion("江苏省 南京市 江宁区");
        u.setIdentity("种植户");
        u.setCrops("番茄,黄瓜,小麦");
        u.setStatus(User.STATUS_NORMAL);
        u.setTokenVersion(2);
        return u;
    }

    private static ResetTicket ticket(String plain, LocalDateTime expireAt) {
        ResetTicket t = new ResetTicket();
        t.setId(7L);
        t.setUserId(10001L);
        t.setPhone(PHONE);
        t.setTicketHash(PasswordResetService.sha256Hex(plain));
        t.setPurpose(TicketPurpose.RESET);
        t.setExpireAt(expireAt);
        t.setUsed(0);
        return t;
    }
}
