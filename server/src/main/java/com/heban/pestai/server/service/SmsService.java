package com.heban.pestai.server.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heban.pestai.server.client.SmsClient;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.entity.SmsCode;
import com.heban.pestai.server.mapper.SmsCodeMapper;
import com.heban.pestai.server.mapper.UserMapper;

/**
 * 验证码：6 位随机数字、5 分钟有效、用后作废；同一手机号同一场景 60 秒内只能发送一次（42901）。
 */
@Service
public class SmsService {

    public static final String ALIYUN_FREQUENCY_MESSAGE = "验证码发送太频繁，同一手机号 1 分钟内只能接收 1 条，请稍后再试";

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    public static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
    public static final Duration CODE_TTL = Duration.ofMinutes(5);

    private final SmsCodeMapper smsCodeMapper;
    private final UserMapper userMapper;
    private final SmsClient smsClient;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SmsService(SmsCodeMapper smsCodeMapper, UserMapper userMapper, SmsClient smsClient, Clock clock) {
        this.smsCodeMapper = smsCodeMapper;
        this.userMapper = userMapper;
        this.smsClient = smsClient;
        this.clock = clock;
    }

    /**
     * 发送验证码。短信发送失败时抛出 50001，事务回滚刚插入的记录，用户可以立即重试，不占用 60 秒额度。
     */
    @Transactional(rollbackFor = Exception.class)
    public void send(String phone, SmsScene scene) {
        checkScene(phone, scene);
        LocalDateTime now = LocalDateTime.now(clock);
        SmsCode latest = smsCodeMapper.selectLatest(phone, scene.name());
        if (latest != null && latest.getCreatedAt() != null
                && latest.getCreatedAt().plus(RESEND_INTERVAL).isAfter(now)) {
            throw new BizException(ErrorCode.TOO_FREQUENT);
        }
        String code = generateCode();
        SmsCode record = new SmsCode();
        record.setPhone(phone);
        record.setScene(scene.name());
        record.setCode(code);
        record.setExpireAt(now.plus(CODE_TTL));
        record.setUsed(0);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        smsCodeMapper.insert(record);
        try {
            smsClient.sendCode(phone, scene, code);
        } catch (SmsClient.SmsSendException e) {
            log.error("短信发送失败：场景 {}，{}", scene, e.getMessage());
            if (e.getKind() == SmsClient.SmsSendException.Kind.FREQUENCY) {
                // 阿里云短信认证对同一手机号默认 60 秒只发 1 条（不分模板 / 场景）
                throw new BizException(ErrorCode.TOO_FREQUENT, ALIYUN_FREQUENCY_MESSAGE);
            }
            throw new BizException(e.getKind() == SmsClient.SmsSendException.Kind.INVALID_PHONE
                    ? ErrorCode.PARAM_ERROR : ErrorCode.SERVER_ERROR);
        }
    }

    /**
     * 校验并核销验证码：只认同手机号、同场景最近一条；未使用、未过期且一致才通过，核销以「used = 0」为条件，
     * 影响行数为 0 说明已被并发请求使用。任一条件不满足抛出 40103。需在调用方事务中执行。
     */
    public void verifyAndConsume(String phone, SmsScene scene, String code) {
        LocalDateTime now = LocalDateTime.now(clock);
        SmsCode latest = smsCodeMapper.selectLatest(phone, scene.name());
        if (latest == null || latest.alreadyUsed() || latest.getExpireAt() == null
                || !latest.getExpireAt().isAfter(now) || code == null || !code.equals(latest.getCode())) {
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        if (smsCodeMapper.markUsed(latest.getId(), now) != 1) {
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
    }

    /** 场景前置检查，避免白发短信 */
    private void checkScene(String phone, SmsScene scene) {
        switch (scene) {
            case RESET -> {
                if (userMapper.selectByPhone(phone) == null) {
                    throw new BizException(ErrorCode.NOT_REGISTERED);
                }
            }
            case REGISTER -> {
                if (userMapper.selectByPhone(phone) != null) {
                    throw new BizException(ErrorCode.PHONE_REGISTERED);
                }
            }
            case REBIND -> {
                // 找回账号：验证码发往新手机号，新手机号已注册时无法换绑
                if (userMapper.selectByPhone(phone) != null) {
                    throw new BizException(ErrorCode.PHONE_REGISTERED, AccountService.PHONE_TAKEN);
                }
            }
            case LOGIN -> {
                // 未注册的手机号验证码登录时自动注册，不做检查
            }
            case CHANGE_PHONE, DEACTIVATE, RECOVERY -> {
                // 只由 AccountService / RecoveryCodeService 在登录状态下调用：新手机号是否与当前相同、是否已注册由它们检查；
                // DEACTIVATE、RECOVERY 的手机号取自当前账号，无需再查
            }
        }
    }

    /** 0–999999 的随机整数左补零为 6 位 */
    String generateCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
