package com.heban.pestai.server.mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.heban.pestai.server.entity.User;

/** t_user（SQL 见 resources/mapper/UserMapper.xml） */
@Mapper
public interface UserMapper {

    /** 按主键读取全部列（含 password_hash、recovery_code_hash，仅供服务端使用） */
    User selectById(@Param("id") Long id);

    /** JwtInterceptor：只读 id、token_version、status */
    User selectAuthInfo(@Param("id") Long id);

    User selectByPhone(@Param("phone") String phone);

    /** 大家常问：只读 crops */
    String selectCropsById(@Param("id") Long id);

    /** 插入新用户，回填自增 id */
    int insert(User user);

    /** 记录刚通过短信验证码证明了手机号归属（验证码登录、注册） */
    int markSmsVerified(@Param("id") long id, @Param("now") LocalDateTime now);

    int updateLastLogin(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 更新资料；avatarUrl 为 null 时保留原值 */
    int updateProfile(User user);

    /** 清理头像：给定的相对路径中仍被某个用户 avatar_url 引用的那些（去重） */
    List<String> selectAvatarUrlsInUse(@Param("urls") Collection<String> urls);

    int updatePasswordAndBumpVersion(@Param("id") Long id,
                                     @Param("passwordHash") String passwordHash,
                                     @Param("now") LocalDateTime now);

    int updatePhoneAndBumpVersion(@Param("id") Long id,
                                  @Param("phone") String phone,
                                  @Param("now") LocalDateTime now);

    /**
     * 找回账号换绑：更新手机号；passwordHash 不为 null 时同时更新密码；clearRecoveryCode 为 true 时清空恢复码
     * （恢复码用于找回账号后立即失效）；token_version + 1，last_login_at 更新为 now（本机直接登录）。
     */
    int updateForRebind(@Param("id") Long id,
                        @Param("phone") String phone,
                        @Param("passwordHash") String passwordHash,
                        @Param("clearRecoveryCode") boolean clearRecoveryCode,
                        @Param("now") LocalDateTime now);

    /** 生成 / 重新生成恢复码：覆盖哈希（旧恢复码立即失效），生成时间为 now */
    int updateRecoveryCode(@Param("id") Long id,
                           @Param("recoveryCodeHash") String recoveryCodeHash,
                           @Param("now") LocalDateTime now);

    /** token_version 原子加 1（退出登录） */
    int increaseTokenVersion(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 注销账号：物理删除用户行（关联数据须先在同一事务中删除） */
    int deleteById(@Param("id") Long id);
}
