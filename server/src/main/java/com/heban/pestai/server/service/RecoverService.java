package com.heban.pestai.server.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.RecoverMethod;
import com.heban.pestai.server.common.Rules;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.entity.ResetTicket;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.ResetTicketMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.security.JwtTokenProvider;
import com.heban.pestai.server.vo.LoginVO;
import com.heban.pestai.server.vo.RebindTicketVO;

/**
 * 找回账号（原手机号已停用时，用本人才知道的信息自助换绑）：
 * <ol>
 *   <li>auth/recover/verify：原手机号 + 登录密码 / 账号恢复码验证身份，签发一次性 rebind_ticket
 *       （32 位随机串，库中只存 SHA-256 摘要，10 分钟有效，绑定到原手机号对应的用户；复用 t_reset_ticket，
 *       purpose 为 REBIND_PASSWORD / REBIND_RECOVERY）。</li>
 *   <li>auth/recover/rebind：凭证 + 新手机号验证码（REBIND），在一个事务内换绑手机号、按需更新密码、
 *       恢复码方式清空恢复码、token_version + 1、更新 last_login_at、凭证置为已用，签发新令牌
 *       （本机直接登录，其他设备失效）。</li>
 * </ol>
 * 密码、恢复码错误次数不限制（产品负责人决定）；恢复码有 60 位熵，在线穷举不可行。
 */
@Service
public class RecoverService {

    private static final Logger log = LoggerFactory.getLogger(RecoverService.class);

    public static final Duration TICKET_TTL = PasswordResetService.TICKET_TTL;
    public static final String TICKET_EXPIRED = "验证已过期，请重新验证身份";
    public static final String SAME_PHONE = "新手机号不能与原手机号相同";
    public static final String NEW_PASSWORD_REQUIRED = "使用恢复码找回账号时必须设置新密码";

    private final UserMapper userMapper;
    private final ResetTicketMapper resetTicketMapper;
    private final SmsService smsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final UserAssembler userAssembler;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RecoverService(UserMapper userMapper, ResetTicketMapper resetTicketMapper, SmsService smsService,
                          PasswordEncoder passwordEncoder, JwtTokenProvider tokenProvider, UserAssembler userAssembler,
                          Clock clock) {
        this.userMapper = userMapper;
        this.resetTicketMapper = resetTicketMapper;
        this.smsService = smsService;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.userAssembler = userAssembler;
        this.clock = clock;
    }

    /**
     * 验证身份：未注册 40104；停用 40301；PASSWORD 方式未设置密码或密码错误 40102；
     * RECOVERY_CODE 方式未生成恢复码、格式不对或不一致 40105。通过后作废该用户此前未使用的凭证并签发新凭证，
     * 明文只出现在这次响应中。恢复码在这一步不失效，换绑成功时才清空。
     */
    @Transactional(rollbackFor = Exception.class)
    public RebindTicketVO verify(String oldPhone, RecoverMethod method, String password, String recoveryCode) {
        if (method == null) {
            throw BizException.param("请选择验证方式");
        }
        User user = userMapper.selectByPhone(oldPhone);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_REGISTERED);
        }
        if (user.isDisabled()) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        switch (method) {
            case PASSWORD -> checkPassword(user, password);
            case RECOVERY_CODE -> checkRecoveryCode(user, recoveryCode);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        resetTicketMapper.invalidateByUser(user.getId(), now);
        String ticket = PasswordResetService.newTicket(random);
        ResetTicket record = new ResetTicket();
        record.setUserId(user.getId());
        record.setPhone(oldPhone);
        record.setTicketHash(PasswordResetService.sha256Hex(ticket));
        record.setPurpose(method.ticketPurpose());
        record.setExpireAt(now.plus(TICKET_TTL));
        record.setUsed(0);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        resetTicketMapper.insert(record);
        log.info("用户 {} 已用{}验证身份，签发找回账号凭证", user.getId(), describe(method));
        return new RebindTicketVO(ticket, (int) TICKET_TTL.toSeconds(), method.name());
    }

    /**
     * 换绑并登录。凭证不存在 / 已用 / 过期 / 用途不是找回账号 / 不属于该原手机号，或签发后账号已换绑、已注销、
     * 恢复码已失效：40001「验证已过期，请重新验证身份」；停用 40301；
     * 恢复码方式缺少新密码 40001；新密码不合规 40001；新号与原号相同 40001；新号已注册 40901；验证码不通过 40103。
     * 前面的检查都在核销验证码之前完成，失败时验证码与凭证都不被消耗；之后任一步失败整体回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginVO rebind(String oldPhone, String rebindTicket, String newPhone, String code, String newPassword) {
        LocalDateTime now = LocalDateTime.now(clock);
        ResetTicket ticket = rebindTicket == null ? null
                : resetTicketMapper.selectByHash(PasswordResetService.sha256Hex(rebindTicket));
        RecoverMethod method = ticket == null ? null : RecoverMethod.fromTicketPurpose(ticket.getPurpose());
        if (ticket == null || method == null || ticket.alreadyUsed() || ticket.getExpireAt() == null
                || !ticket.getExpireAt().isAfter(now) || !oldPhone.equals(ticket.getPhone())) {
            throw BizException.param(TICKET_EXPIRED);
        }
        User user = userMapper.selectById(ticket.getUserId());
        if (user == null || !oldPhone.equals(user.getPhone())
                || (method == RecoverMethod.RECOVERY_CODE && !user.recoveryCodeGenerated())) {
            throw BizException.param(TICKET_EXPIRED);
        }
        if (user.isDisabled()) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        boolean withPassword = newPassword != null && !newPassword.isEmpty();
        if (withPassword && !Rules.isValidPassword(newPassword)) {
            throw BizException.param(Rules.PASSWORD_MESSAGE);
        }
        if (method == RecoverMethod.RECOVERY_CODE && !withPassword) {
            throw BizException.param(NEW_PASSWORD_REQUIRED);
        }
        if (newPhone.equals(oldPhone)) {
            throw BizException.param(SAME_PHONE);
        }
        if (userMapper.selectByPhone(newPhone) != null) {
            throw new BizException(ErrorCode.PHONE_REGISTERED, AccountService.PHONE_TAKEN);
        }
        smsService.verifyAndConsume(newPhone, SmsScene.REBIND, code);

        String passwordHash = withPassword ? passwordEncoder.encode(newPassword) : null;
        boolean clearRecoveryCode = method == RecoverMethod.RECOVERY_CODE;
        int updated;
        try {
            updated = userMapper.updateForRebind(user.getId(), newPhone, passwordHash, clearRecoveryCode, now);
        } catch (DuplicateKeyException e) {
            // 两个请求同时抢同一新号：唯一索引 uk_phone 兜底
            throw new BizException(ErrorCode.PHONE_REGISTERED, AccountService.PHONE_TAKEN);
        }
        // 同一事务内回查：UPDATE 已持有行锁，读到的就是本次加 1 之后的 token_version
        User changed = updated == 1 ? userMapper.selectById(user.getId()) : null;
        if (changed == null) {
            throw BizException.param(TICKET_EXPIRED);
        }
        if (resetTicketMapper.markUsed(ticket.getId(), now) != 1) {
            // 凭证已被并发请求用掉：抛出异常，上面的换绑随事务回滚
            throw BizException.param(TICKET_EXPIRED);
        }
        int version = changed.getTokenVersion() == null ? 0 : changed.getTokenVersion();
        log.info("用户 {} 已用{}找回账号并换绑手机号（新号尾号 {}），{}token_version → {}", user.getId(), describe(method),
                tail(newPhone), clearRecoveryCode ? "已设置新密码并清空恢复码，" : (withPassword ? "已设置新密码，" : ""),
                version);
        return new LoginVO(tokenProvider.issue(user.getId(), version), userAssembler.toVO(changed), false);
    }

    /** 未设置密码（验证码注册的账号）同样按密码错误处理 */
    private void checkPassword(User user, String password) {
        String hash = user.getPasswordHash();
        if (password == null || hash == null || hash.isBlank() || !passwordEncoder.matches(password, hash)) {
            throw new BizException(ErrorCode.WRONG_PASSWORD);
        }
    }

    /** 未生成、已用于找回账号（已清空）、格式不对、不一致都返回 40105 */
    private void checkRecoveryCode(User user, String recoveryCode) {
        String normalized = RecoveryCodes.normalize(recoveryCode);
        if (normalized == null || !user.recoveryCodeGenerated()
                || !passwordEncoder.matches(normalized, user.getRecoveryCodeHash())) {
            throw new BizException(ErrorCode.RECOVERY_CODE_INVALID);
        }
    }

    private static String describe(RecoverMethod method) {
        return method == RecoverMethod.PASSWORD ? "登录密码" : "恢复码";
    }

    private static String tail(String phone) {
        return phone == null || phone.length() < 4 ? "" : phone.substring(phone.length() - 4);
    }
}
