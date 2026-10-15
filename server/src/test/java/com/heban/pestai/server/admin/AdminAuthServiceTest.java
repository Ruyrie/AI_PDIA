package com.heban.pestai.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpSession;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.heban.pestai.server.admin.entity.Admin;
import com.heban.pestai.server.admin.mapper.AdminMapper;
import com.heban.pestai.server.admin.vo.AdminLoginVO;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.config.PestaiProperties;

/** 后台登录：成功（新会话 + CSRF）、失败、按账号与按 IP 锁定、修改密码规则、初始管理员。 */
class AdminAuthServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 9, 0);
    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    private AdminMapper adminMapper;
    private AdminLogService logService;
    private LoginAttemptLimiter limiter;
    private AdminAuthService service;
    private Admin admin;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        adminMapper = mock(AdminMapper.class);
        logService = mock(AdminLogService.class);
        limiter = new LoginAttemptLimiter();
        service = new AdminAuthService(adminMapper, limiter, logService, ENCODER, clock);
        admin = admin("Initial-Pass-2026", true);
        when(adminMapper.selectByUsername("admin")).thenReturn(admin);
        when(adminMapper.selectById(1L)).thenReturn(admin);
    }

    @AfterEach
    void tearDown() {
        AdminContext.clear();
    }

    private static Admin admin(String password, boolean mustChange) {
        Admin a = new Admin();
        a.setId(1L);
        a.setUsername("admin");
        a.setPasswordHash(ENCODER.encode(password));
        a.setMustChangePassword(mustChange);
        a.setFailedAttempts(0);
        return a;
    }

    private static MockHttpServletRequest request(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", ip);
        return request;
    }

    @Test
    void loginCreatesFreshSessionWithCsrfTokenAndRecordsSuccess() {
        MockHttpServletRequest request = request("203.0.113.7");
        MockHttpSession old = new MockHttpSession();
        old.setAttribute("stale", "x");
        request.setSession(old);

        AdminLoginVO vo = service.login(" admin ", "Initial-Pass-2026", request);

        assertThat(vo.username()).isEqualTo("admin");
        assertThat(vo.mustChangePassword()).isTrue();
        assertThat(vo.csrfToken()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(old.isInvalid()).isTrue();
        HttpSession session = request.getSession(false);
        assertThat(session).isNotSameAs(old);
        assertThat(session.getAttribute(AdminAuthService.ATTR_ADMIN_ID)).isEqualTo(1L);
        assertThat(session.getAttribute(AdminAuthService.ATTR_CSRF_TOKEN)).isEqualTo(vo.csrfToken());
        assertThat(session.getAttribute("stale")).isNull();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(30 * 60);
        verify(adminMapper).recordSuccess(1L, "203.0.113.7", NOW);
        verify(logService).record(eq(1L), eq("admin"), eq(AdminActions.LOGIN), anyString(), anyString(), eq("203.0.113.7"));
    }

    @Test
    void wrongPasswordAndUnknownUserAreBoth40102AndLogged() {
        assertBiz(() -> service.login("admin", "wrong-password1", request("203.0.113.8")), ErrorCode.WRONG_PASSWORD, null);
        verify(adminMapper).recordFailure(eq(1L), eq(5), eq(NOW.plusMinutes(15)), eq(NOW));
        assertBiz(() -> service.login("nobody", "wrong-password1", request("203.0.113.8")), ErrorCode.WRONG_PASSWORD, null);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(logService, org.mockito.Mockito.times(2)).record(any(), anyString(), eq(AdminActions.LOGIN_FAILED),
                anyString(), detail.capture(), eq("203.0.113.8"));
        assertThat(detail.getAllValues()).allSatisfy(d -> assertThat(d).doesNotContain("wrong-password1"));
        verify(adminMapper, never()).recordSuccess(anyLong(), anyString(), any());
    }

    @Test
    void fiveFailuresFromOneIpLockThatIpFor15Minutes() {
        for (int i = 1; i <= 4; i++) {
            assertBiz(() -> service.login("nobody", "x", request("198.51.100.1")), ErrorCode.WRONG_PASSWORD, null);
        }
        assertBiz(() -> service.login("nobody", "x", request("198.51.100.1")), ErrorCode.TOO_FREQUENT,
                "登录失败次数过多，请 15 分钟后再试");
        // 锁定期间即使密码正确也拒绝，且不再校验密码
        assertBiz(() -> service.login("admin", "Initial-Pass-2026", request("198.51.100.1")), ErrorCode.TOO_FREQUENT,
                "登录失败次数过多，请 15 分钟后再试");
        verify(adminMapper, never()).recordSuccess(anyLong(), anyString(), any());
        // 其他 IP 不受影响
        assertThat(service.login("admin", "Initial-Pass-2026", request("198.51.100.2")).username()).isEqualTo("admin");
    }

    @Test
    void lockedAccountIsRejectedFromAnyIp() {
        // 第 5 次失败时数据库把账号锁定（recordFailure 之后回查到 locked_until）
        Admin lockedAfter = admin("Initial-Pass-2026", true);
        lockedAfter.setLockedUntil(NOW.plusMinutes(15));
        when(adminMapper.selectById(1L)).thenReturn(lockedAfter);
        assertBiz(() -> service.login("admin", "bad", request("192.0.2.1")), ErrorCode.TOO_FREQUENT,
                "登录失败次数过多，请 15 分钟后再试");

        when(adminMapper.selectByUsername("admin")).thenReturn(lockedAfter);
        lockedAfter.setLockedUntil(NOW.plusMinutes(3).plusSeconds(10));
        assertBiz(() -> service.login("admin", "Initial-Pass-2026", request("192.0.2.99")), ErrorCode.TOO_FREQUENT,
                "登录失败次数过多，请 4 分钟后再试");
        verify(adminMapper, never()).recordSuccess(anyLong(), anyString(), any());

        // 锁定到期后可以登录
        lockedAfter.setLockedUntil(NOW.minusSeconds(1));
        assertThat(service.login("admin", "Initial-Pass-2026", request("192.0.2.99")).username()).isEqualTo("admin");
    }

    @Test
    void changePasswordRules() {
        AdminContext.set(new AdminContext.Principal(1L, "admin", "127.0.0.1"));
        assertBiz(() -> service.changePassword("wrong", "NewPassword2026"), ErrorCode.PARAM_ERROR, "原密码不正确");
        assertBiz(() -> service.changePassword("Initial-Pass-2026", "short1"), ErrorCode.PARAM_ERROR,
                "新密码至少 10 位，并同时包含字母和数字");
        assertBiz(() -> service.changePassword("Initial-Pass-2026", "onlyletterspassword"), ErrorCode.PARAM_ERROR,
                "新密码至少 10 位，并同时包含字母和数字");
        assertBiz(() -> service.changePassword("Initial-Pass-2026", "1234567890123"), ErrorCode.PARAM_ERROR,
                "新密码至少 10 位，并同时包含字母和数字");
        assertBiz(() -> service.changePassword("Initial-Pass-2026", "Initial-Pass-2026"), ErrorCode.PARAM_ERROR,
                "新密码不能与原密码相同");
        verify(adminMapper, never()).updatePassword(anyLong(), anyString(), any());

        AtomicReference<String> hash = new AtomicReference<>();
        when(adminMapper.updatePassword(eq(1L), anyString(), eq(NOW))).thenAnswer(inv -> {
            hash.set(inv.getArgument(1));
            return 1;
        });
        service.changePassword("Initial-Pass-2026", "Garden2026Secure");
        assertThat(ENCODER.matches("Garden2026Secure", hash.get())).isTrue();
        verify(logService).log(eq(AdminActions.CHANGE_PASSWORD), anyString(), eq("修改了初始密码"));

        assertBiz(() -> service.verifyCurrentPassword("nope"), ErrorCode.PARAM_ERROR, "管理员密码不正确");
        assertBiz(() -> service.verifyCurrentPassword(null), ErrorCode.PARAM_ERROR, "管理员密码不正确");
        service.verifyCurrentPassword("Initial-Pass-2026");
    }

    @Test
    void lockMessageRoundsUpMinutes() {
        assertThat(AdminAuthService.locked(Duration.ofSeconds(30)).getMessage()).isEqualTo("登录失败次数过多，请 1 分钟后再试");
        assertThat(AdminAuthService.locked(Duration.ofMinutes(15)).getMessage()).isEqualTo("登录失败次数过多，请 15 分钟后再试");
        assertThat(AdminAuthService.locked(Duration.ofSeconds(61)).getMessage()).isEqualTo("登录失败次数过多，请 2 分钟后再试");
    }

    @Test
    void initialAdminUsesConfiguredPasswordOrRandomOneOnlyWhenTableIsEmpty() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T01:00:00Z"), ZONE);
        PestaiProperties properties = new PestaiProperties();
        properties.getAdmin().setInitialPassword("from-env-0123abcd");
        AdminBootstrap bootstrap = new AdminBootstrap(adminMapper, ENCODER, properties, clock);

        when(adminMapper.countAll()).thenReturn(1L);
        assertThat(bootstrap.createInitialAdminIfAbsent()).isFalse();
        verify(adminMapper, never()).insert(any());

        when(adminMapper.countAll()).thenReturn(0L);
        ArgumentCaptor<Admin> created = ArgumentCaptor.forClass(Admin.class);
        assertThat(bootstrap.createInitialAdminIfAbsent()).isTrue();
        verify(adminMapper).insert(created.capture());
        assertThat(created.getValue().getUsername()).isEqualTo("admin");
        assertThat(created.getValue().getMustChangePassword()).isTrue();
        assertThat(ENCODER.matches("from-env-0123abcd", created.getValue().getPasswordHash())).isTrue();

        // 表不存在（未执行迁移 006）：只记日志，不影响启动
        when(adminMapper.countAll()).thenThrow(new RuntimeException("Table 'pestai.t_admin' doesn't exist"));
        assertThat(bootstrap.createInitialAdminIfAbsent()).isFalse();

        String random = RandomPasswords.generate(new java.security.SecureRandom(), 12);
        assertThat(random).hasSize(12).matches("[A-Za-z0-9]+").matches(".*\\d.*").matches(".*[A-Za-z].*");
        assertThat(AdminAuthService.isValidPassword(RandomPasswords.generate(new java.security.SecureRandom(), 16))).isTrue();
    }

    @Test
    void ipLimiterExpiresStaleFailures() {
        Instant t0 = Instant.parse("2026-10-14T01:00:00Z");
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.recordFailure("10.0.0.1", t0)).isFalse();
        }
        // 15 分钟后重新计数
        assertThat(limiter.recordFailure("10.0.0.1", t0.plus(Duration.ofMinutes(16)))).isFalse();
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("10.0.0.1", t0.plus(Duration.ofMinutes(16)));
        }
        assertThat(limiter.recordFailure("10.0.0.1", t0.plus(Duration.ofMinutes(16)))).isTrue();
        assertThat(limiter.lockRemaining("10.0.0.1", t0.plus(Duration.ofMinutes(20)))).isEqualTo(Duration.ofMinutes(11));
        assertThat(limiter.lockRemaining("10.0.0.1", t0.plus(Duration.ofMinutes(32)))).isNull();
        limiter.cleanup(t0.plus(Duration.ofHours(1)));
        assertThat(limiter.size()).isZero();
        limiter.recordFailure("10.0.0.2", t0);
        limiter.reset("10.0.0.2");
        assertThat(limiter.size()).isZero();
    }

    @Test
    void clientIpTrustsProxyHeadersOnlyFromLoopback() {
        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("203.0.113.9");
        direct.addHeader("X-Real-IP", "1.1.1.1");
        assertThat(ClientIp.of(direct)).isEqualTo("203.0.113.9");

        MockHttpServletRequest viaNginx = new MockHttpServletRequest();
        viaNginx.setRemoteAddr("127.0.0.1");
        viaNginx.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.10");
        assertThat(ClientIp.of(viaNginx)).isEqualTo("203.0.113.10");
        viaNginx.addHeader("X-Real-IP", "203.0.113.11");
        assertThat(ClientIp.of(viaNginx)).isEqualTo("203.0.113.11");

        MockHttpServletRequest forged = new MockHttpServletRequest();
        forged.setRemoteAddr("0:0:0:0:0:0:0:1");
        forged.addHeader("X-Real-IP", "<script>");
        assertThat(ClientIp.of(forged)).isEqualTo("0:0:0:0:0:0:0:1");
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
}
