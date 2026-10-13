package com.heban.pestai.server.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.heban.pestai.server.entity.AiSession;
import com.heban.pestai.server.entity.AiSessionSummary;

/** t_ai_session */
@Mapper
public interface AiSessionMapper {

    int insert(AiSession session);

    AiSession selectById(@Param("id") Long id);

    /** 写入标题，同时更新 updated_at */
    int updateTitle(@Param("id") Long id, @Param("title") String title, @Param("now") LocalDateTime now);

    /** 每写入一条消息更新 updated_at（历史对话按它倒序） */
    int touch(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 历史对话：该用户至少有 1 条消息的会话数 */
    long countWithMessagesByUser(@Param("userId") Long userId);

    /** 历史对话：至少有 1 条消息的会话，updated_at 倒序、id 倒序分页，带消息条数与最后一条可显示消息 */
    List<AiSessionSummary> selectPageWithMessages(@Param("userId") Long userId,
                                                  @Param("offset") long offset,
                                                  @Param("size") int size);

    /** 删除会话（条件 id、user_id；消息须先删除） */
    int deleteByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /** 注销账号：删除该用户的全部会话（消息须先删除） */
    int deleteByUser(@Param("userId") Long userId);
}
