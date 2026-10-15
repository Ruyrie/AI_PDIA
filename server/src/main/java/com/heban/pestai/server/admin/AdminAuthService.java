package com.heban.pestai.server.admin;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.heban.pestai.server.admin.entity.Admin;
import com.heban.pestai.server.admin.mapper.AdminMapper;
import com.heban.pestai.server.admin.vo.AdminLoginVO;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;

/**
 * 后台登录、退出、当前管理员、修改密码（docs/后台管理接口.md「1.1 auth」）。
 * <ul>
 *   <li>登录：同一 IP（LoginAttemptLimiter，内存）或同一账号（t_admin.failed_attempts / locked_until）连续输错 5 次
 *       锁定 15 分钟，期间返回 42901「登录失败次数过多，请 N 分钟后再试」（锁定期间的请求不校验密码、不记日志）；
 *       用户名不存在与密码错误同样返回 40102「账号或密码错误」，并照常做一次 BCrypt 比较，避免按耗时猜出用户名；</li>
 *   <li>登录成功：作废旧会话、建立新会话（新的会话 ID，防会话固定），会话中保存管理员 ID 与随机的 CSRF 令牌；</li>
 *   <li>登录成功 / 失败、退出、修改密码都写操作日志（不记录密码）。</li>
 * </ul>
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    public static final String ATTR_ADMIN_ID = "PESTAI_ADMIN_ID";
    public static final String ATTR_CSRF_TOKEN = "PESTAI_ADMIN_CSRF";
    /** 30 分钟无操作过期（与 server.servlet.session.timeout 一致；MockMvc 等不读该配置的环境也生效） */
    public static final int SESSION_TIMEOUT_SECONDS = 30 * 60;

    public static final int PASSWORD_MIN = 10;
    public static final int PASSWORD_MAX = 64;
    static final String PASSWORD_RULE = "新密码至少 10 位，并同时包含字母和数字";
    static final String OLD_PASSWORD_WRONG = "原密码不正确";
    static final String SAME_PASSWORD = "新密码不能与原密码相同";

    private final AdminMapper adminMapper;
    private final LoginAttemptLimiter ipLimiter;
    private final AdminLogService logService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** 用户名不存在时用来做一次同样耗时的 BCrypt 比较 */
    private final String dummyHash;

    public AdminAuthService(AdminMapper adminMapper, LoginAttemptLimiter ipLimiter, AdminLogService logService,
                            PasswordEncoder passwordEncoder, Clock clock) {
        this.adminMapper = adminMapper;
        this.ipLimiter = ipLimiter;
        this.logService = logService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("pestai-admin-dummy-password-0");
    }

    public AdminLoginVO login(String username, String password, HttpServletRequest request) {
        String name = username == null ? "" : username.trim();
        String ip = ClientIp.of(request);
        Instant instant = clock.instant();
        LocalDateTime now = LocalDateTime.now(clock);

        Duration ipLock = ipLimiter.lockRemaining(ip, instant);
        if (ipLock != null) {
            throw locked(ipLock);
        }
        Admin admin = adminMapper.selectByUsername(name);
        if (admin != null && admin.getLockedUntil() != null && admin.getLockedUntil().isAfter(now)) {
            throw locked(Duration.between(now, admin.getLockedUntil()));
        }
        boolean ok;
        if (admin == null) {
            passwordEncoder.matches(password, dummyHash);
            ok = false;
        } else {
            ok = admin.getPasswordHash() != null && passwordEncoder.matches(password, admin.getPasswordHash());
        }
        if (!ok) {
            boolean ipLocked = ipLimiter.recordFailure(ip, instant);
            boolean accountLocked = false;
            if (admin != null) {
                adminMapper.recordFailure(admin.getId(), LoginAttemptLimiter.MAX_FAILURES,
                        now.plus(LoginAttemptLimiter.LOCK_DURATION), now);
                Admin after = adminMapper.selectById(admin.getId());
                accountLocked = after != null && after.getLockedUntil() != null && after.getLockedUntil().isAfter(now);
            }
            boolean lockedNow = ipLocked || accountLocked;
            logService.record(admin == null ? null : admin.getId(), name, AdminActions.LOGIN_FAILED, "管理员 " + name,
                    (admin == null ? "用户名不存在" : "密码错误") + (lockedNow ? "，已连续 5 次，锁定 15 分钟" : ""), ip);
            if (lockedNow) {
                log.warn("后台登录连续失败 {} 次，锁定 15 分钟：用户名 {}，IP {}", LoginAttemptLimiter.MAX_FAILURES, name, ip);
                throw locked(LoginAttemptLimiter.LOCK_DURATION);
            }
            throw new BizException(ErrorCode.WRONG_PASSWORD);
        }

        ipLimiter.reset(ip);
        adminMapper.recordSuccess(admin.getId(), ip, now);
        HttpSession old = request.getSession(false);
        if (old != null) {
            try {
                old.invalidate();
            } catch (IllegalStateException e) {
                // 已失效
            }
        }
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(SESSION_TIMEOUT_SECONDS);
        String csrf = newToken();
        session.setAttribute(ATTR_ADMIN_ID, admin.getId());
        session.setAttribute(ATTR_CSRF_TOKEN, csrf);
        logService.record(admin.getId(), admin.getUsername(), AdminActions.LOGIN, "管理员 " + admin.getUsername(),
                admin.mustChange() ? "登录成功（需先修改初始密码）" : "登录成功", ip);
        log.info("后台管理员 {} 登录，IP {}", admin.getUsername(), ip);
        return new AdminLoginVO(admin.getUsername(), admin.mustChange(), csrf);
    }

    /** auth/me：拦截器已确认登录，CSRF 令牌取自会话 */
    public AdminLoginVO me(HttpServletRequest request) {
        AdminContext.Principal principal = AdminContext.require();
        Admin admin = requireAdmin(principal.id());
        HttpSession session = request.getSession(false);
        Object csrf = session == null ? null : session.getAttribute(ATTR_CSRF_TOKEN);
        return new AdminLoginVO(admin.getUsername(), admin.mustChange(), csrf instanceof String s ? s : null);
    }

    /** auth/logout：先写日志再作废会话 */
    public void logout(HttpServletRequest request) {
        AdminContext.Principal principal = AdminContext.require();
        logService.log(AdminActions.LOGOUT, "管理员 " + principal.username(), "退出登录");
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException e) {
                // 已失效
            }
        }
    }

    /** auth/password：原密码错误 40001；新密码 ≥ 10 位且同时含字母和数字、不能与原密码相同；成功后 must_change_password = 0 */
    public void changePassword(String oldPassword, String newPassword) {
        AdminContext.Principal principal = AdminContext.require();
        Admin admin = requireAdmin(principal.id());
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, admin.getPasswordHash())) {
            throw BizException.param(OLD_PASSWORD_WRONG);
        }
        if (!isValidPassword(newPassword)) {
            throw BizException.param(PASSWORD_RULE);
        }
        if (newPassword.equals(oldPassword)) {
            throw BizException.param(SAME_PASSWORD);
        }
        adminMapper.updatePassword(admin.getId(), passwordEncoder.encode(newPassword), LocalDateTime.now(clock));
        logService.log(AdminActions.CHANGE_PASSWORD, "管理员 " + admin.getUsername(),
                admin.mustChange() ? "修改了初始密码" : "修改了密码");
        log.info("后台管理员 {} 修改了密码", admin.getUsername());
    }

    /** 校验当前管理员的密码（修改 / 恢复密钥类配置时）；不正确 40001「管理员密码不正确」 */
    public void verifyCurrentPassword(String password) {
        Admin admin = requireAdmin(AdminContext.require().id());
        if (password == null || password.isEmpty() || !passwordEncoder.matches(password, admin.getPasswordHash())) {
            throw BizException.param("管理员密码不正确");
        }
    }

    /** 10–64 位，同时包含字母和数字 */
    static boolean isValidPassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            return false;
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                letter = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            }
        }
        return letter && digit;
    }

    private Admin requireAdmin(long id) {
        Admin admin = adminMapper.selectById(id);
        if (admin == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID, AdminInterceptor.NOT_LOGGED_IN);
        }
        return admin;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static BizException locked(Duration remaining) {
        long minutes = Math.max(1, (remaining.getSeconds() + 59) / 60);
        return new BizException(ErrorCode.TOO_FREQUENT, "登录失败次数过多，请 " + minutes + " 分钟后再试");
    }
}
