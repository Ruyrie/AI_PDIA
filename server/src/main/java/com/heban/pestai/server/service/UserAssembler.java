package com.heban.pestai.server.service;

import org.springframework.stereotype.Component;

import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.vo.UserVO;

/**
 * User → UserVO：只复制 7 个界面字段，avatar_url 相对路径拼成完整地址；
 * password_hash、salt、recovery_code_hash、recovery_code_created_at、status、token_version、last_login_at 等内部字段不进入 VO。
 */
@Component
public class UserAssembler {

    private final FileStorageService fileStorageService;

    public UserAssembler(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    public UserVO toVO(User user) {
        if (user == null) {
            return null;
        }
        return new UserVO(
                user.getId(),
                user.getPhone(),
                user.getNickname(),
                fileStorageService.toUrl(user.getAvatarUrl()),
                user.getRegion() == null ? "" : user.getRegion(),
                user.getIdentity(),
                user.getCrops() == null || user.getCrops().isBlank() ? null : user.getCrops());
    }
}
