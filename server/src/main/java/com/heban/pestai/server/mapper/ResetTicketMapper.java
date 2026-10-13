package com.heban.pestai.server.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.heban.pestai.server.entity.ResetTicket;

/** t_reset_ticket（找回密码 reset_ticket 与找回账号 rebind_ticket，按 purpose 区分） */
@Mapper
public interface ResetTicketMapper {

    ResetTicket selectByHash(@Param("ticketHash") String ticketHash);

    int insert(ResetTicket ticket);

    /** 作废该用户所有未使用的凭证（不分用途：同一时间每个用户只保留最新签发的一张） */
    int invalidateByUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 作废：条件 id 且 used = 0 */
    int markUsed(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 定时清理 */
    int deleteCreatedBefore(@Param("before") LocalDateTime before);

    /** 注销账号：删除该用户的全部凭证 */
    int deleteByUser(@Param("userId") Long userId);
}
