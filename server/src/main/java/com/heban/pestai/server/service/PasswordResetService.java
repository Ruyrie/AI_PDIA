package com.heban.pestai.server.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.common.TicketPurpose;
import com.heban.pestai.server.entity.ResetTicket;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.ResetTicketMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.vo.ResetTicketVO;

/**
 * 找回密码：校验验证码换取一次性 reset_ticket（只存 SHA-256 摘要，10 分钟有效，purpose = RESET），
 * 凭证重置密码后 token_version + 1。找回账号的 rebind_ticket 与它共用 t_reset_ticket，但不能用于重置密码。
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    public static final Duration TICKET_TTL = Duration.ofMinutes(10);
    public static final String TICKET_EXPIRED = "验证已过期，请重新获取验证码";
    public static final String SAME_PASSWORD = "新密码不能与最近使用过的密码相同";

    private final UserMapper userMapper;
    private final ResetTicketMapper resetTicketMapper;
    private final SmsService smsService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(UserMapper userMapper, ResetTicketMapper resetTicketMapper, SmsService smsService,
                                PasswordEncoder passwordEncoder, Clock clock) {
        this.userMapper = userMapper;
        this.resetTicketMapper = resetTicketMapper;
        this.smsService = smsService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** 校验验证码（场景 RESET），作废旧凭证并生成新凭证；明文只出现在这次响应中 */
    @Transactional(rollbackFor = Exception.class)
    public ResetTicketVO verify(String phone, String code) {
        User user = userMapper.selectByPhone(phone);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_REGISTERED);
        }
        smsService.verifyAndConsume(phone, SmsScene.RESET, code);
        LocalDateTime now = LocalDateTime.now(clock);
        resetTicketMapper.invalidateByUser(user.getId(), now);

        String ticket = newTicket(random);

        ResetTicket record = new ResetTicket();
        record.setUserId(user.getId());
        record.setPhone(phone);
        record.setTicketHash(sha256Hex(ticket));
        record.setPurpose(TicketPurpose.RESET);
        record.setExpireAt(now.plus(TICKET_TTL));
        record.setUsed(0);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        resetTicketMapper.insert(record);
        return new ResetTicketVO(ticket, (int) TICKET_TTL.toSeconds());
    }

    /**
     * 凭证重置密码：凭证无效（含用途不是 RESET 的找回账号凭证）40001「验证已过期…」；
     * 新旧密码相同 40001「新密码不能与…」（凭证保持未使用）。成功后该用户其他未使用的凭证一并作废，
     * 避免旧密码泄露时他人用此前拿到的找回账号凭证继续换绑。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reset(String phone, String resetTicket, String newPassword) {
        LocalDateTime now = LocalDateTime.now(clock);
        ResetTicket ticket = resetTicketMapper.selectByHash(sha256Hex(resetTicket));
        if (ticket == null || ticket.alreadyUsed() || ticket.getExpireAt() == null
                || !ticket.getExpireAt().isAfter(now) || !phone.equals(ticket.getPhone())
                || !TicketPurpose.RESET.equals(ticket.getPurpose())) {
            throw BizException.param(TICKET_EXPIRED);
        }
        User user = userMapper.selectById(ticket.getUserId());
        // 期间账号已找回换绑或更换手机号、手机号已变更时，旧凭证同样作废
        if (user == null || !phone.equals(user.getPhone())) {
            throw BizException.param(TICKET_EXPIRED);
        }
        String oldHash = user.getPasswordHash();
        if (oldHash != null && !oldHash.isBlank() && passwordEncoder.matches(newPassword, oldHash)) {
            throw BizException.param(SAME_PASSWORD);
        }
        userMapper.updatePasswordAndBumpVersion(user.getId(), passwordEncoder.encode(newPassword), now);
        if (resetTicketMapper.markUsed(ticket.getId(), now) != 1) {
            // 凭证已被并发请求用掉：抛出异常，上一步的密码更新随事务回滚
            throw BizException.param(TICKET_EXPIRED);
        }
        resetTicketMapper.invalidateByUser(user.getId(), now);
    }

    /** 每天凌晨 3 点删除 7 天前的凭证记录，避免表无限增长 */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")
    public void cleanExpiredTickets() {
        int deleted = resetTicketMapper.deleteCreatedBefore(LocalDateTime.now(clock).minusDays(7));
        if (deleted > 0) {
            log.info("已清理 {} 条过期的找回密码 / 找回账号凭证", deleted);
        }
    }

    /** 一次性凭证明文：16 字节随机数的十六进制（32 位）；找回密码与找回账号共用 */
    public static String newTicket(SecureRandom random) {
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
