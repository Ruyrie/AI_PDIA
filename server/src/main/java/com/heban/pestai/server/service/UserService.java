package com.heban.pestai.server.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.request.ProfileRequest;
import com.heban.pestai.server.vo.UploadVO;
import com.heban.pestai.server.vo.UserVO;

/**
 * 当前用户资料、完善 / 修改资料、上传头像。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserMapper userMapper;
    private final UserAssembler userAssembler;
    private final FileStorageService fileStorageService;
    private final Clock clock;

    public UserService(UserMapper userMapper, UserAssembler userAssembler, FileStorageService fileStorageService,
                       Clock clock) {
        this.userMapper = userMapper;
        this.userAssembler = userAssembler;
        this.fileStorageService = fileStorageService;
        this.clock = clock;
    }

    /** user/me：只读；用户不存在或已停用时按登录失效返回 40101 */
    @Transactional(readOnly = true)
    public UserVO getCurrentUser(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.isDisabled()) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        return userAssembler.toVO(user);
    }

    /** user/avatar：保存到 avatar 目录，不写数据表（保存资料时才生效；一直没有保存的由 ChatImageJanitor 定期清理） */
    public UploadVO uploadAvatar(MultipartFile file) {
        String relative = fileStorageService.save(file, FileStorageService.AVATAR);
        return new UploadVO(fileStorageService.toUrl(relative));
    }

    /**
     * user/profile：头像地址转相对路径、规范作物字符串、更新后回查。
     * 换成了新头像时，事务提交后删除旧头像文件；avatar_url 为空（保留原头像）或与原头像相同时不删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateProfile(long userId, ProfileRequest request) {
        String avatar = null;
        String oldAvatar = null;
        if (request.getAvatarUrl() != null && !request.getAvatarUrl().isBlank()) {
            avatar = fileStorageService.toRelative(request.getAvatarUrl(), FileStorageService.AVATAR);
            if (avatar == null) {
                throw BizException.param("头像地址无效，请重新上传头像");
            }
            User before = userMapper.selectById(userId);
            oldAvatar = before == null ? null : before.getAvatarUrl();
        }
        User user = new User();
        user.setId(userId);
        user.setNickname(request.getNickname().trim());
        user.setAvatarUrl(avatar);
        user.setRegion(request.getRegion() == null ? "" : request.getRegion().trim());
        user.setIdentity(request.getIdentity());
        user.setCrops(normalizeCrops(request.getCrops()));
        user.setUpdatedAt(LocalDateTime.now(clock));
        if (userMapper.updateProfile(user) != 1) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        if (avatar != null && oldAvatar != null && !oldAvatar.equals(avatar)) {
            deleteOldAvatarAfterCommit(userId, oldAvatar);
        }
        return userAssembler.toVO(userMapper.selectById(userId));
    }

    /**
     * 换头像后（或后台清除头像后），事务提交成功再尽力删除旧头像文件：只处理 avatar/ 下的相对路径；仍被其他账号引用
     * （地址可以由 App 传入）或核对引用失败时不删除，也不影响保存资料（没有被引用的旧头像之后由 ChatImageJanitor 兜底清理）。
     * 须在已把该用户的 avatar_url 改掉的同一事务中调用。
     * 删除失败只记日志（afterCommit 中的异常会传给调用方，因此全部吞掉）；没有事务时（单元测试直接调用）立即执行。
     */
    public void deleteOldAvatarAfterCommit(long userId, String oldAvatar) {
        if (!oldAvatar.startsWith(FileStorageService.AVATAR + "/")) {
            return;
        }
        try {
            // 本用户已在同一事务中改为新头像，查到的只会是其他账号的引用
            List<String> inUse = userMapper.selectAvatarUrlsInUse(List.of(oldAvatar));
            if (inUse == null || !inUse.isEmpty()) {
                return;
            }
        } catch (RuntimeException e) {
            log.warn("用户 {} 更换头像：核对旧头像 {} 的引用失败，暂不删除：{}", userId, oldAvatar, e.getMessage());
            return;
        }
        Runnable task = () -> {
            try {
                if (!fileStorageService.deleteQuietly(oldAvatar)) {
                    log.warn("用户 {} 更换头像：旧头像 {} 未能删除", userId, oldAvatar);
                }
            } catch (RuntimeException e) {
                log.warn("用户 {} 更换头像：删除旧头像 {} 失败：{}", userId, oldAvatar, e.getMessage());
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

    /** 按逗号（含中文逗号、顿号）拆分，去掉首尾空格、空项与重复项后用英文逗号拼接；为空返回 null */
    public static String normalizeCrops(String crops) {
        if (crops == null || crops.isBlank()) {
            return null;
        }
        Set<String> items = new LinkedHashSet<>();
        for (String item : crops.split("[,，、]")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items.isEmpty() ? null : String.join(",", items);
    }
}
