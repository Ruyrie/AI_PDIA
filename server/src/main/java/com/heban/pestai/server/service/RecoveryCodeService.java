package com.heban.pestai.server.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.ResetTicketMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.vo.RecoveryCodeVO;

/**
 * 账号恢复码（需要登录，用户 ID 只取自令牌）：查询状态；向<b>当前手机号</b>发送验证码（RECOVERY）；
 * 核销验证码后生成 / 重新生成恢复码 —— 用 SecureRandom 生成，库中只存 BCrypt 哈希，明文只在这次响应中返回。
 * 重新生成后旧恢复码立即失效，此前签发、尚未使用的找回账号凭证也一并作废。
 */
@Service
public class RecoveryCodeService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryCodeService.class);

    /** 刚通过短信验证手机号后，这段时间内生成恢复码不需要再次短信验证 */
    public static final java.time.Duration VERIFY_WINDOW = java.time.Duration.ofMinutes(10);
    public static final String VERIFY_FIRST = "请先验证当前手机号";

    private final UserMapper userMapper;
    private final ResetTicketMapper resetTicketMapper;
    private final SmsService smsService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RecoveryCodeService(UserMapper userMapper, ResetTicketMapper resetTicketMapper, SmsService smsService,
                               PasswordEncoder passwordEncoder, Clock clock) {
        this.userMapper = userMapper;
        this.resetTicketMapper = resetTicketMapper;
        this.smsService = smsService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** GET user/recovery-code：是否已生成及生成时间；recovery_code 恒为 null（明文不可再次查看） */
    @Transactional(readOnly = true)
    public RecoveryCodeVO status(long userId) {
        User user = requireUser(userId);
        boolean generated = user.recoveryCodeGenerated();
        return new RecoveryCodeVO(generated, generated ? user.getRecoveryCodeCreatedAt() : null, null,
                !recentlyVerified(user));
    }

    /** POST user/recovery-code/sms：验证码只发往当前账号的手机号（不接受客户端传入号码）；42901 由 SmsService 负责 */
    public void sendCode(long userId) {
        User user = requireUser(userId);
        smsService.send(user.getPhone(), SmsScene.RECOVERY);
    }

    /**
     * POST user/recovery-code：核销当前手机号的验证码（40103）→ 生成新恢复码并覆盖哈希（旧码失效）→
     * 作废该用户未使用的找回账号 / 找回密码凭证 → 返回显示形式的明文（ABCD-EFGH-JKLM）。
     */
    @Transactional(rollbackFor = Exception.class)
    public RecoveryCodeVO generate(long userId, String code) {
        User user = requireUser(userId);
        if (code == null || code.isBlank()) {
            // 不带验证码：只允许刚通过短信验证过手机号的用户（如验证码登录、注册后 10 分钟内）
            if (!recentlyVerified(user)) {
                throw BizException.param(VERIFY_FIRST);
            }
        } else {
            smsService.verifyAndConsume(user.getPhone(), SmsScene.RECOVERY, code);
        }

        String plain = RecoveryCodes.generate(random);
        // 列为 DATETIME（秒），先截掉毫秒，保证这次返回的时间与之后查询到的一致
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        if (userMapper.updateRecoveryCode(userId, passwordEncoder.encode(plain), now) != 1) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        // 用旧恢复码（或密码）签发、尚未使用的凭证一并作废，旧码拿到的凭证不能再用来换绑
        resetTicketMapper.invalidateByUser(userId, now);
        log.info("用户 {} 已{}账号恢复码", userId, user.recoveryCodeGenerated() ? "重新生成" : "生成");
        return new RecoveryCodeVO(true, now, RecoveryCodes.format(plain), !recentlyVerified(user));
    }

    private boolean recentlyVerified(User user) {
        return user.smsVerifiedWithin(VERIFY_WINDOW, LocalDateTime.now(clock));
    }

    /** 用户不存在或已停用按登录失效处理（拦截器之后被并发删除 / 停用的情况） */
    private User requireUser(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.isDisabled()) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        return user;
    }
}
