package com.heban.pestai.server.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.Identity;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.security.JwtTokenProvider;
import com.heban.pestai.server.vo.LoginVO;

/**
 * 密码登录、验证码登录（未注册自动注册）、注册、退出登录。
 */
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final SmsService smsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final UserAssembler userAssembler;
    private final Clock clock;

    public AuthService(UserMapper userMapper, SmsService smsService, PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider, UserAssembler userAssembler, Clock clock) {
        this.userMapper = userMapper;
        this.smsService = smsService;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.userAssembler = userAssembler;
        this.clock = clock;
    }

    /** 密码登录：未注册 40104；停用 40301；无密码或密码错误 40102 */
    public LoginVO loginByPassword(String phone, String password) {
        User user = userMapper.selectByPhone(phone);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_REGISTERED);
        }
        if (user.isDisabled()) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        String hash = user.getPasswordHash();
        if (hash == null || hash.isBlank() || !passwordEncoder.matches(password, hash)) {
            throw new BizException(ErrorCode.WRONG_PASSWORD);
        }
        return issueLogin(user, false);
    }

    /** 验证码登录：核销验证码、未注册时自动创建账号（is_new = true）、签发令牌，在同一事务中完成 */
    @Transactional(rollbackFor = Exception.class)
    public LoginVO loginBySms(String phone, String code) {
        smsService.verifyAndConsume(phone, SmsScene.LOGIN, code);
        User user = userMapper.selectByPhone(phone);
        boolean isNew = false;
        if (user == null) {
            try {
                user = insertDefaultUser(phone, null);
                isNew = true;
            } catch (DuplicateKeyException e) {
                // 两个请求同时为同一新手机号建号：唯一约束兜底，重新查询已创建的用户
                user = userMapper.selectByPhone(phone);
                if (user == null) {
                    throw e;
                }
            }
        }
        if (user.isDisabled()) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        markSmsVerified(user);
        return issueLogin(user, isNew);
    }

    /** 注册：已注册 40901；验证码不通过 40103；密码规则由请求校验保证 */
    @Transactional(rollbackFor = Exception.class)
    public LoginVO register(String phone, String code, String password) {
        if (userMapper.selectByPhone(phone) != null) {
            throw new BizException(ErrorCode.PHONE_REGISTERED);
        }
        smsService.verifyAndConsume(phone, SmsScene.REGISTER, code);
        User user;
        try {
            user = insertDefaultUser(phone, passwordEncoder.encode(password));
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.PHONE_REGISTERED);
        }
        markSmsVerified(user);
        // 注册成功后 App 固定进入完善资料页，is_new 固定为 false
        return issueLogin(user, false);
    }

    /** 退出登录：token_version 加 1，该账号已签发的全部令牌立即失效；影响行数为 0 同样按成功处理 */
    @Transactional(rollbackFor = Exception.class)
    public void logout(long userId) {
        userMapper.increaseTokenVersion(userId, LocalDateTime.now(clock));
    }

    private User insertDefaultUser(String phone, String passwordHash) {
        LocalDateTime now = LocalDateTime.now(clock);
        User user = new User();
        user.setPhone(phone);
        user.setPasswordHash(passwordHash);
        user.setNickname(defaultNickname(phone));
        user.setRegion("");
        user.setIdentity(Identity.GROWER);
        user.setStatus(User.STATUS_NORMAL);
        user.setTokenVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return user;
    }

    /** 刚用短信验证码证明了手机号归属：10 分钟内生成恢复码等操作无需再次短信验证 */
    private void markSmsVerified(User user) {
        LocalDateTime now = LocalDateTime.now(clock);
        userMapper.markSmsVerified(user.getId(), now);
        user.setSmsVerifiedAt(now);
    }

    private LoginVO issueLogin(User user, boolean isNew) {
        int version = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        String token = tokenProvider.issue(user.getId(), version);
        LocalDateTime now = LocalDateTime.now(clock);
        userMapper.updateLastLogin(user.getId(), now);
        user.setLastLoginAt(now);
        return new LoginVO(token, userAssembler.toVO(user), isNew);
    }

    /** 默认昵称：「农友」+ 手机号后 4 位 */
    static String defaultNickname(String phone) {
        String tail = phone.length() >= 4 ? phone.substring(phone.length() - 4) : phone;
        return "农友" + tail;
    }
}
