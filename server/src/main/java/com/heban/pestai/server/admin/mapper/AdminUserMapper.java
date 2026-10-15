package com.heban.pestai.server.admin.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.heban.pestai.server.admin.entity.AdminUserRow;
import com.heban.pestai.server.entity.Recognition;

/**
 * 后台用户管理（SQL 见 resources/mapper/AdminUserMapper.xml）。App 侧已有的语句（重置密码 + token_version、
 * 退出登录、会话列表、删除会话、注销账号）直接复用 UserMapper / AiSessionMapper / AccountService，这里只放后台独有的。
 */
@Mapper
public interface AdminUserMapper {

    /**
     * keyword 匹配手机号或昵称（包含即可，调用方已转义 % _ \）；status 为 null 时不过滤
     */
    long countUsers(@Param("keyword") String keyword, @Param("status") Integer status);

    /** 用户列表：id 倒序（新注册的在前），每行带识别次数与 AI 提问次数 */
    List<AdminUserRow> selectUserPage(@Param("keyword") String keyword,
                                      @Param("status") Integer status,
                                      @Param("offset") long offset,
                                      @Param("size") int size);

    /** 用户详情（含 region、sms_verified_at、是否已生成恢复码） */
    AdminUserRow selectUserRow(@Param("id") Long id);

    /** 停用 / 启用：bumpTokenVersion 为 true 时 token_version + 1（停用时所有设备立即下线） */
    int updateStatus(@Param("id") Long id,
                     @Param("status") int status,
                     @Param("bumpTokenVersion") boolean bumpTokenVersion,
                     @Param("now") LocalDateTime now);

    /**
     * 后台修改资料：clearIdentity 为 true 时 identity 置为 NULL（未设置），否则 identity 不为 null 时更新、为 null 时保留原值；
     * updateCrops 为 true 时 crops 更新为给定值（null 即清空），false 时保留原值；clearAvatar 为 true 时清空头像
     */
    int updateProfile(@Param("id") Long id,
                      @Param("nickname") String nickname,
                      @Param("identity") String identity,
                      @Param("clearIdentity") boolean clearIdentity,
                      @Param("crops") String crops,
                      @Param("updateCrops") boolean updateCrops,
                      @Param("clearAvatar") boolean clearAvatar,
                      @Param("now") LocalDateTime now);

    /** 该用户的全部识别记录数（含用户已删除的） */
    long countRecognitions(@Param("userId") Long userId);

    /** 该用户的识别记录（含已删除的），created_at、id 倒序 */
    List<Recognition> selectRecognitionPage(@Param("userId") Long userId,
                                            @Param("offset") long offset,
                                            @Param("size") int size);

    /** 彻底删除前读取：id、user_id、image_url（不带 deleted 条件） */
    Recognition selectRecognitionFile(@Param("id") Long id);

    /** 物理删除识别记录 */
    int deleteRecognition(@Param("id") Long id);
}
