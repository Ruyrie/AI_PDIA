package com.heban.pestai.server.admin.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.heban.pestai.server.admin.entity.Admin;

/** t_admin（SQL 见 resources/mapper/AdminMapper.xml） */
@Mapper
public interface AdminMapper {

    /** 管理员数量（启动时为 0 则创建初始管理员 admin） */
    long countAll();

    Admin selectById(@Param("id") Long id);

    Admin selectByUsername(@Param("username") String username);

    /** 插入管理员，回填自增 id */
    int insert(Admin admin);

    /**
     * 记一次密码错误：失败次数 + 1；达到 threshold 时锁定到 lockUntil 并把失败次数清零（之后重新计数）。
     * 一条 UPDATE 完成，并发的错误尝试不会少算。
     */
    int recordFailure(@Param("id") Long id,
                      @Param("threshold") int threshold,
                      @Param("lockUntil") LocalDateTime lockUntil,
                      @Param("now") LocalDateTime now);

    /** 登录成功：失败次数清零、解除锁定，记录登录时间与 IP */
    int recordSuccess(@Param("id") Long id, @Param("ip") String ip, @Param("now") LocalDateTime now);

    /** 修改密码：同时把 must_change_password 置为 0 */
    int updatePassword(@Param("id") Long id,
                       @Param("passwordHash") String passwordHash,
                       @Param("now") LocalDateTime now);
}
