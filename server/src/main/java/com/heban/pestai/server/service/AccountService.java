package com.heban.pestai.server.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;
import com.heban.pestai.server.mapper.RecognitionMapper;
import com.heban.pestai.server.mapper.ResetTicketMapper;
import com.heban.pestai.server.mapper.SmsCodeMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.security.JwtTokenProvider;
import com.heban.pestai.server.vo.LoginVO;

/**
 * 更换手机号、注销账号（都需要登录，用户 ID 只取自令牌）。
 * <ul>
 *   <li>更换手机号：只验证<b>新手机号</b>收到的验证码（CHANGE_PHONE），原手机号可能已不可用；换绑后 token_version + 1，
 *       按新版本号签发令牌返回，本机保持登录，其他设备上的令牌全部失效。</li>
 *   <li>注销账号：验证<b>当前手机号</b>收到的验证码（DEACTIVATE）后，在一个事务内删除该用户的全部数据，
 *       事务提交后尽力删除磁盘上的头像、识别照片、聊天图片；之后旧令牌因用户不存在返回 40101，该手机号可立即重新注册。</li>
 *   <li>后台注销（deactivateByAdmin）：删除的数据相同，不需要验证码、不发短信。</li>
 * </ul>
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    public static final String SAME_PHONE = "新手机号不能与当前手机号相同";
    public static final String PHONE_TAKEN = "该手机号已注册，请先注销该账号或更换其他号码";

    private final UserMapper userMapper;
    private final RecognitionMapper recognitionMapper;
    private final AiSessionMapper aiSessionMapper;
    private final AiMessageMapper aiMessageMapper;
    private final ResetTicketMapper resetTicketMapper;
    private final SmsCodeMapper smsCodeMapper;
    private final SmsService smsService;
    private final FileStorageService fileStorageService;
    private final JwtTokenProvider tokenProvider;
    private final UserAssembler userAssembler;
    private final Clock clock;

    public AccountService(UserMapper userMapper, RecognitionMapper recognitionMapper, AiSessionMapper aiSessionMapper,
                          AiMessageMapper aiMessageMapper, ResetTicketMapper resetTicketMapper,
                          SmsCodeMapper smsCodeMapper, SmsService smsService,
                          FileStorageService fileStorageService, JwtTokenProvider tokenProvider,
                          UserAssembler userAssembler, Clock clock) {
        this.userMapper = userMapper;
        this.recognitionMapper = recognitionMapper;
        this.aiSessionMapper = aiSessionMapper;
        this.aiMessageMapper = aiMessageMapper;
        this.resetTicketMapper = resetTicketMapper;
        this.smsCodeMapper = smsCodeMapper;
        this.smsService = smsService;
        this.fileStorageService = fileStorageService;
        this.tokenProvider = tokenProvider;
        this.userAssembler = userAssembler;
        this.clock = clock;
    }

    /**
     * user/phone/sms：向新手机号发送验证码。与当前手机号相同 40001；已被其他账号注册 40901（先查再发，避免白发短信）；
     * 同手机号同场景 60 秒限频 42901 由 SmsService 负责。
     */
    public void sendChangePhoneCode(long userId, String newPhone) {
        User user = requireUser(userId);
        if (newPhone.equals(user.getPhone())) {
            throw BizException.param(SAME_PHONE);
        }
        if (userMapper.selectByPhone(newPhone) != null) {
            throw new BizException(ErrorCode.PHONE_REGISTERED, PHONE_TAKEN);
        }
        smsService.send(newPhone, SmsScene.CHANGE_PHONE);
    }

    /**
     * user/phone：校验并核销新手机号的验证码（40103）→ 再次确认新号未被注册（40901）→ 更新 phone、token_version + 1 →
     * 按新 token_version 签发令牌。并发时两个账号抢同一新号由唯一索引 uk_phone 兜底，重复键转 40901，事务回滚（验证码不被消耗）。
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginVO changePhone(long userId, String newPhone, String code) {
        User user = requireUser(userId);
        if (newPhone.equals(user.getPhone())) {
            throw BizException.param(SAME_PHONE);
        }
        smsService.verifyAndConsume(newPhone, SmsScene.CHANGE_PHONE, code);
        if (userMapper.selectByPhone(newPhone) != null) {
            throw new BizException(ErrorCode.PHONE_REGISTERED, PHONE_TAKEN);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int updated;
        try {
            updated = userMapper.updatePhoneAndBumpVersion(userId, newPhone, now);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.PHONE_REGISTERED, PHONE_TAKEN);
        }
        // 同一事务内回查：UPDATE 已持有行锁，读到的就是本次加 1 之后的 token_version
        User changed = updated == 1 ? userMapper.selectById(userId) : null;
        if (changed == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        int version = changed.getTokenVersion() == null ? 0 : changed.getTokenVersion();
        log.info("用户 {} 已更换手机号（新号尾号 {}），token_version → {}", userId, tail(newPhone), version);
        return new LoginVO(tokenProvider.issue(userId, version), userAssembler.toVO(changed), false);
    }

    /** user/deactivate/sms：验证码只发往当前账号的手机号（不接受客户端传入号码）；42901 由 SmsService 负责 */
    public void sendDeactivateCode(long userId) {
        User user = requireUser(userId);
        smsService.send(user.getPhone(), SmsScene.DEACTIVATE);
    }

    /**
     * user/deactivate：校验并核销当前手机号的验证码（40103）后，在一个事务内依次删除
     * t_ai_message_image、t_ai_message（属于该用户的会话）→ t_ai_session → t_recognition（含已逻辑删除的）→ t_reset_ticket →
     * 该手机号的 t_sms_code → 最后删除 t_user（恢复码随用户行一起删除）。
     * 要删除的文件路径在删除数据行之前查出（聊天图片取 t_ai_message_image 与 t_ai_message.image_url 两处），
     * 事务提交后再删磁盘文件（回滚时文件保留）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deactivate(long userId, String code) {
        User user = requireUser(userId);
        smsService.verifyAndConsume(user.getPhone(), SmsScene.DEACTIVATE, code);
        deleteAccountData(userId, user, "");
    }

    /**
     * 后台注销账号（docs/后台管理接口.md「三、用户管理」）：不需要验证码、不发短信，已停用的账号同样可以注销；
     * 删除的数据与文件和用户自己注销完全相同。用户不存在 40001。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deactivateByAdmin(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.param("用户不存在");
        }
        deleteAccountData(userId, user, "（后台操作）");
    }

    /** 注销的数据清理（须在事务中）：先查出文件路径，再按外键顺序删除数据行，事务提交后删除文件 */
    private void deleteAccountData(long userId, User user, String note) {
        String phone = user.getPhone();
        Set<String> files = new LinkedHashSet<>();
        addFile(files, user.getAvatarUrl());
        recognitionMapper.selectAllImageUrlsByUser(userId).forEach(path -> addFile(files, path));
        aiMessageMapper.selectImageUrlsByUser(userId).forEach(path -> addFile(files, path));

        aiMessageMapper.deleteImagesByUser(userId);
        aiMessageMapper.deleteByUser(userId);
        aiSessionMapper.deleteByUser(userId);
        recognitionMapper.deleteAllByUser(userId);
        resetTicketMapper.deleteByUser(userId);
        smsCodeMapper.deleteByPhone(phone);
        if (userMapper.deleteById(userId) != 1) {
            // 用户已被并发请求删除：整体回滚
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        log.info("用户 {} 已注销{}（手机号尾号 {}），待清理文件 {} 个", userId, note, tail(phone), files.size());
        deleteFilesAfterCommit(files);
    }

    /** 用户不存在或已停用按登录失效处理（拦截器之后被并发删除 / 停用的情况） */
    private User requireUser(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.isDisabled()) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        return user;
    }

    /**
     * 事务提交成功后尽力删除文件；删除失败只记日志，不影响注销结果（afterCommit 中抛出的异常会传给调用方，因此全部吞掉）。
     * 没有事务时（单元测试直接调用）立即执行。
     */
    private void deleteFilesAfterCommit(Collection<String> files) {
        if (files.isEmpty()) {
            return;
        }
        List<String> snapshot = List.copyOf(files);
        Runnable task = () -> {
            int failed = 0;
            for (String path : snapshot) {
                try {
                    if (!fileStorageService.deleteQuietly(path)) {
                        failed++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("注销账号：删除文件 {} 失败：{}", path, e.getMessage());
                }
            }
            if (failed > 0) {
                log.warn("注销账号：{} 个文件中有 {} 个未能删除", snapshot.size(), failed);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private static void addFile(Set<String> files, String relative) {
        if (relative != null && !relative.isBlank()) {
            files.add(relative.trim());
        }
    }

    private static String tail(String phone) {
        return phone == null || phone.length() < 4 ? "" : phone.substring(phone.length() - 4);
    }
}
