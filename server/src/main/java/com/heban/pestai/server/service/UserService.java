package com.heban.pestai.server.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    /** user/avatar：保存到 avatar 目录，不写数据表（保存资料时才生效） */
    public UploadVO uploadAvatar(MultipartFile file) {
        String relative = fileStorageService.save(file, FileStorageService.AVATAR);
        return new UploadVO(fileStorageService.toUrl(relative));
    }

    /** user/profile：头像地址转相对路径、规范作物字符串、更新后回查 */
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateProfile(long userId, ProfileRequest request) {
        String avatar = null;
        if (request.getAvatarUrl() != null && !request.getAvatarUrl().isBlank()) {
            avatar = fileStorageService.toRelative(request.getAvatarUrl(), FileStorageService.AVATAR);
            if (avatar == null) {
                throw BizException.param("头像地址无效，请重新上传头像");
            }
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
        return userAssembler.toVO(userMapper.selectById(userId));
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
