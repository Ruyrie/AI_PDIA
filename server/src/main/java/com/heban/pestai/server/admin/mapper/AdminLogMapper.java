package com.heban.pestai.server.admin.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.heban.pestai.server.admin.entity.AdminLog;

/** t_admin_log（SQL 见 resources/mapper/AdminLogMapper.xml） */
@Mapper
public interface AdminLogMapper {

    int insert(AdminLog log);

    /** action 为空时不按动作过滤 */
    long count(@Param("action") String action);

    /** 按时间倒序分页（created_at、id 倒序）；action 为空时不按动作过滤 */
    List<AdminLog> selectPage(@Param("action") String action,
                              @Param("offset") long offset,
                              @Param("size") int size);
}
