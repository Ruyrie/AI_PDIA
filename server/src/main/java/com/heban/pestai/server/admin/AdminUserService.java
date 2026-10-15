package com.heban.pestai.server.admin;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.heban.pestai.server.admin.entity.AdminUserRow;
import com.heban.pestai.server.admin.mapper.AdminUserMapper;
import com.heban.pestai.server.admin.request.AdminProfileRequest;
import com.heban.pestai.server.admin.vo.AdminMessageVO;
import com.heban.pestai.server.admin.vo.AdminRecognitionVO;
import com.heban.pestai.server.admin.vo.AdminSessionVO;
import com.heban.pestai.server.admin.vo.AdminUserDetailVO;
import com.heban.pestai.server.admin.vo.AdminUserRowVO;
import com.heban.pestai.server.admin.vo.TempPasswordVO;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiSession;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.service.AccountService;
import com.heban.pestai.server.service.AiSessionService;
import com.heban.pestai.server.service.ChatImages;
import com.heban.pestai.server.service.FileStorageService;
import com.heban.pestai.server.service.RecognitionAssembler;
import com.heban.pestai.server.service.UserService;
import com.heban.pestai.server.vo.PageVO;

/**
 * 后台用户管理（docs/后台管理接口.md「三、用户管理」）。App 已有的逻辑直接复用：
 * <ul>
 *   <li>停用：t_user.status = 1 且 token_version + 1（所有设备立即下线；之后 App 登录返回 40301「该账号已停用」）；</li>
 *   <li>强制下线：token_version + 1（UserMapper.increaseTokenVersion，与 App 退出登录相同）；</li>
 *   <li>修改资料：作物按 UserService.normalizeCrops 规范；身份只接受三种之一，空串表示清除为「未设置」（NULL），
 *       不给出表示不修改（作物同理）；
 *       清除头像时事务提交后删除头像文件（UserService.deleteOldAvatarAfterCommit，仍被其他账号引用时保留）；</li>
 *   <li>重置密码：12 位随机临时密码（字母 + 数字），BCrypt 保存并 token_version + 1（UserMapper.updatePasswordAndBumpVersion）；</li>
 *   <li>删除 AI 会话：AiSessionService.delete（以会话所属用户的身份调用，绕过「只能删自己的」检查）；</li>
 *   <li>注销：AccountService.deactivateByAdmin（与用户自己注销删除的数据相同，不需要验证码、不发短信）。</li>
 * </ul>
 * 每个写操作成功后写操作日志（事务提交后才写，见 AdminLogService）。
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    public static final String STATUS_NORMAL = "NORMAL";
    public static final String STATUS_DISABLED = "DISABLED";
    static final String USER_NOT_FOUND = "用户不存在";
    static final String SESSION_NOT_FOUND = "会话不存在";
    static final String PHONE_MISMATCH = "手机号不一致，请输入该用户的完整手机号";
    static final int TEMP_PASSWORD_LENGTH = 12;

    private final AdminUserMapper adminUserMapper;
    private final UserMapper userMapper;
    private final AiSessionMapper aiSessionMapper;
    private final AiMessageMapper aiMessageMapper;
    private final AiSessionService aiSessionService;
    private final AccountService accountService;
    private final UserService userService;
    private final FileStorageService fileStorageService;
    private final AdminLogService logService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AdminUserService(AdminUserMapper adminUserMapper, UserMapper userMapper, AiSessionMapper aiSessionMapper,
                            AiMessageMapper aiMessageMapper, AiSessionService aiSessionService,
                            AccountService accountService, UserService userService,
                            FileStorageService fileStorageService, AdminLogService logService,
                            PasswordEncoder passwordEncoder, Clock clock) {
        this.adminUserMapper = adminUserMapper;
        this.userMapper = userMapper;
        this.aiSessionMapper = aiSessionMapper;
        this.aiMessageMapper = aiMessageMapper;
        this.aiSessionService = aiSessionService;
        this.accountService = accountService;
        this.userService = userService;
        this.fileStorageService = fileStorageService;
        this.logService = logService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** GET users：keyword 匹配手机号或昵称；status 为 NORMAL / DISABLED / 空 */
    @Transactional(readOnly = true)
    public PageVO<AdminUserRowVO> page(String keyword, String status, int page, int size) {
        String kw = keyword == null || keyword.isBlank() ? null : escapeLike(keyword.trim());
        Integer statusValue = parseStatus(status);
        long total = adminUserMapper.countUsers(kw, statusValue);
        long offset = (long) (page - 1) * size;
        if (total == 0 || offset >= total) {
            return PageVO.empty(total);
        }
        List<AdminUserRowVO> list = adminUserMapper.selectUserPage(kw, statusValue, offset, size)
                .stream().map(this::toRow).toList();
        return new PageVO<>(list, total, (long) page * size < total);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailVO detail(long userId) {
        return toDetail(requireRow(userId));
    }

    /** 用户的识别记录（含用户已删除的） */
    @Transactional(readOnly = true)
    public PageVO<AdminRecognitionVO> recognitions(long userId, int page, int size) {
        requireUser(userId);
        long total = adminUserMapper.countRecognitions(userId);
        long offset = (long) (page - 1) * size;
        if (total == 0 || offset >= total) {
            return PageVO.empty(total);
        }
        List<AdminRecognitionVO> list = adminUserMapper.selectRecognitionPage(userId, offset, size)
                .stream().map(this::toRecognition).toList();
        return new PageVO<>(list, total, (long) page * size < total);
    }

    /** 用户的 AI 会话（与 App 历史对话相同：至少有 1 条消息，updated_at 倒序） */
    @Transactional(readOnly = true)
    public PageVO<AdminSessionVO> sessions(long userId, int page, int size) {
        requireUser(userId);
        long total = aiSessionMapper.countWithMessagesByUser(userId);
        long offset = (long) (page - 1) * size;
        if (total == 0 || offset >= total) {
            return PageVO.empty(total);
        }
        List<AdminSessionVO> list = aiSessionMapper.selectPageWithMessages(userId, offset, size).stream()
                .map(s -> new AdminSessionVO(s.getId(),
                        s.getTitle() == null || s.getTitle().isBlank() ? AiSessionService.DEFAULT_TITLE : s.getTitle(),
                        s.getMessageCount() == null ? 0 : s.getMessageCount(), s.getUpdatedAt()))
                .toList();
        return new PageVO<>(list, total, (long) page * size < total);
    }

    /** 会话的全部消息（正序，只读）；图片为完整地址；AI 回答带所用模型与是否备用 */
    @Transactional(readOnly = true)
    public List<AdminMessageVO> messages(long sessionId) {
        if (aiSessionMapper.selectById(sessionId) == null) {
            throw BizException.param(SESSION_NOT_FOUND);
        }
        List<AiMessage> messages = ChatImages.attach(aiMessageMapper, aiMessageMapper.selectBySession(sessionId));
        return messages.stream()
                .map(m -> new AdminMessageVO(m.getRole(), m.getContent() == null ? "" : m.getContent(),
                        ChatImages.of(m).stream().map(fileStorageService::toUrl).toList(),
                        m.getReasoningContent(), m.getCreatedAt(), m.getModel(), Boolean.TRUE.equals(m.getFallback())))
                .toList();
    }

    /** 停用并强制下线；已停用的再次停用同样成功（再次让令牌失效） */
    @Transactional(rollbackFor = Exception.class)
    public void disable(long userId, String reason) {
        User user = requireUser(userId);
        adminUserMapper.updateStatus(userId, User.STATUS_DISABLED, true, LocalDateTime.now(clock));
        String why = reason == null || reason.isBlank() ? "未填写原因" : "原因：" + reason.trim();
        logService.log(AdminActions.USER_DISABLE, target(user), "停用并强制下线；" + why);
        log.info("后台停用用户 {}", userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void enable(long userId) {
        User user = requireUser(userId);
        adminUserMapper.updateStatus(userId, User.STATUS_NORMAL, false, LocalDateTime.now(clock));
        logService.log(AdminActions.USER_ENABLE, target(user), "恢复正常");
    }

    /** 强制下线：token_version + 1，所有设备需重新登录 */
    @Transactional(rollbackFor = Exception.class)
    public void forceLogout(long userId) {
        User user = requireUser(userId);
        userMapper.increaseTokenVersion(userId, LocalDateTime.now(clock));
        logService.log(AdminActions.USER_LOGOUT, target(user), "强制下线（所有设备需重新登录）");
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminUserDetailVO updateProfile(long userId, AdminProfileRequest request) {
        User user = requireUser(userId);
        String nickname = request.getNickname().trim();
        if (nickname.isEmpty()) {
            throw BizException.param("请填写昵称");
        }
        // identity：null（未给出）→ 不改；"" → 清除为 NULL（未设置）；三种身份之一 → 更新（其他值已由请求校验返回 40001）
        String rawIdentity = request.getIdentity();
        boolean clearIdentity = rawIdentity != null && rawIdentity.isEmpty();
        String identity = clearIdentity ? null : rawIdentity;
        // crops：null（未给出）→ 不改；"" → 清空；其他按 App 相同的规则规范（拆分后为空同样清空）
        boolean updateCrops = request.getCrops() != null;
        String crops = updateCrops ? UserService.normalizeCrops(request.getCrops()) : null;
        boolean clearAvatar = Boolean.TRUE.equals(request.getClearAvatar());
        String oldAvatar = user.getAvatarUrl();
        adminUserMapper.updateProfile(userId, nickname, identity, clearIdentity, crops, updateCrops, clearAvatar,
                LocalDateTime.now(clock));
        if (clearAvatar && oldAvatar != null && !oldAvatar.isBlank()) {
            userService.deleteOldAvatarAfterCommit(userId, oldAvatar);
        }
        StringBuilder detail = new StringBuilder("昵称：").append(nickname);
        if (clearIdentity) {
            detail.append("；身份：（清除）");
        } else if (identity != null) {
            detail.append("；身份：").append(identity);
        }
        if (updateCrops) {
            detail.append("；作物：").append(crops == null ? "（清空）" : crops);
        }
        if (clearAvatar) {
            detail.append("；清除头像");
        }
        logService.log(AdminActions.USER_PROFILE, target(user), detail.toString());
        return toDetail(requireRow(userId));
    }

    /** 重置密码：返回 12 位临时密码（只返回这一次，不写日志），同时强制下线 */
    @Transactional(rollbackFor = Exception.class)
    public TempPasswordVO resetPassword(long userId) {
        User user = requireUser(userId);
        String temp = RandomPasswords.generate(random, TEMP_PASSWORD_LENGTH);
        userMapper.updatePasswordAndBumpVersion(userId, passwordEncoder.encode(temp), LocalDateTime.now(clock));
        logService.log(AdminActions.USER_RESET_PASSWORD, target(user), "重置为临时密码并强制下线（密码不记录）");
        return new TempPasswordVO(temp);
    }

    /** 彻底删除识别记录（含用户已删除的）：删除数据行，事务提交后删除照片文件；记录不存在直接成功 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRecognition(long recognitionId) {
        Recognition record = adminUserMapper.selectRecognitionFile(recognitionId);
        if (record == null) {
            return;
        }
        adminUserMapper.deleteRecognition(recognitionId);
        String image = record.getImageUrl();
        if (image != null && !image.isBlank()) {
            deleteFileAfterCommit(image.trim());
        }
        logService.log(AdminActions.RECOGNITION_DELETE, "识别记录 " + recognitionId,
                "用户 " + record.getUserId() + " 的识别记录及照片已彻底删除");
    }

    /** 删除 AI 会话（消息、图片行与聊天图片）；会话不存在直接成功 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(long sessionId) {
        AiSession session = aiSessionMapper.selectById(sessionId);
        if (session == null || session.getUserId() == null) {
            return;
        }
        aiSessionService.delete(session.getUserId(), sessionId);
        logService.log(AdminActions.SESSION_DELETE, "会话 " + sessionId,
                "用户 " + session.getUserId() + " 的会话「" + (session.getTitle() == null ? AiSessionService.DEFAULT_TITLE
                        : session.getTitle()) + "」已删除");
    }

    /** 注销账号：confirm_phone 必须等于完整手机号；删除全部数据，不发短信 */
    @Transactional(rollbackFor = Exception.class)
    public void deactivate(long userId, String confirmPhone) {
        User user = requireUser(userId);
        if (confirmPhone == null || !confirmPhone.trim().equals(user.getPhone())) {
            throw BizException.param(PHONE_MISMATCH);
        }
        accountService.deactivateByAdmin(userId);
        logService.log(AdminActions.USER_DEACTIVATE, target(user), "注销账号并删除全部数据（未发短信）");
    }

    private User requireUser(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.param(USER_NOT_FOUND);
        }
        return user;
    }

    private AdminUserRow requireRow(long userId) {
        AdminUserRow row = adminUserMapper.selectUserRow(userId);
        if (row == null) {
            throw BizException.param(USER_NOT_FOUND);
        }
        return row;
    }

    private static String target(User user) {
        return "用户 " + user.getId() + "（" + user.getPhone() + "）";
    }

    /** NORMAL → 0，DISABLED → 1，空 → 不过滤；其他 40001 */
    static Integer parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim()) {
            case STATUS_NORMAL -> User.STATUS_NORMAL;
            case STATUS_DISABLED -> User.STATUS_DISABLED;
            default -> throw BizException.param("status 只能是 NORMAL 或 DISABLED");
        };
    }

    /** LIKE 通配符转义（MySQL 默认转义符为反斜杠） */
    static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private AdminUserRowVO toRow(AdminUserRow r) {
        return new AdminUserRowVO(r.getId(), r.getPhone(), r.getNickname(), fileStorageService.toUrl(r.getAvatarUrl()),
                r.getIdentity(), blankToNull(r.getCrops()), statusOf(r.getStatus()), r.getCreatedAt(), r.getLastLoginAt(),
                zero(r.getRecognitionCount()), zero(r.getAiQuestionCount()));
    }

    private AdminUserDetailVO toDetail(AdminUserRow r) {
        long sessions = aiSessionMapper.countWithMessagesByUser(r.getId());
        return new AdminUserDetailVO(r.getId(), r.getPhone(), r.getNickname(), fileStorageService.toUrl(r.getAvatarUrl()),
                r.getIdentity(), blankToNull(r.getCrops()), statusOf(r.getStatus()), r.getCreatedAt(), r.getLastLoginAt(),
                zero(r.getRecognitionCount()), zero(r.getAiQuestionCount()),
                r.getRegion() == null ? "" : r.getRegion(), Boolean.TRUE.equals(r.getRecoveryCodeGenerated()),
                r.getSmsVerifiedAt(), sessions);
    }

    private AdminRecognitionVO toRecognition(Recognition r) {
        boolean success = RecognitionStatus.SUCCESS.equals(r.getStatus());
        return new AdminRecognitionVO(r.getId(), r.getMode(), r.getStatus(),
                success ? r.getTopName() : null,
                success ? RecognitionAssembler.normalize(r.getConfidence()) : null,
                success ? r.getSeverity() : null,
                fileStorageService.toUrl(r.getImageUrl()), r.getCreatedAt(),
                r.getDeleted() != null && r.getDeleted() == 1,
                r.getModel(), Boolean.TRUE.equals(r.getFallback()));
    }

    static String statusOf(Integer status) {
        return status != null && status == User.STATUS_DISABLED ? STATUS_DISABLED : STATUS_NORMAL;
    }

    private static long zero(Long value) {
        return value == null ? 0 : value;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 事务提交后尽力删除文件（失败只记日志）；没有事务时立即执行 */
    private void deleteFileAfterCommit(String relative) {
        Runnable task = () -> {
            try {
                if (!fileStorageService.deleteQuietly(relative)) {
                    log.warn("后台删除识别记录：照片 {} 未能删除", relative);
                }
            } catch (RuntimeException e) {
                log.warn("后台删除识别记录：删除照片 {} 失败：{}", relative, e.getMessage());
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
}
