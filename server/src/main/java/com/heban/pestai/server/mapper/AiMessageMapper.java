package com.heban.pestai.server.mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiMessageImage;

/** t_ai_message 与 t_ai_message_image（带图提问的图片，一条消息最多 9 张） */
@Mapper
public interface AiMessageMapper {

    /** 插入消息（含 reasoning_content、reasoning_ms），回填自增 id */
    int insert(AiMessage message);

    /** 带图提问：按顺序插入该消息的图片行（sort_no 依次为 0、1、2…）；imageUrls 不能为空 */
    int insertImages(@Param("messageId") Long messageId,
                     @Param("imageUrls") List<String> imageUrls,
                     @Param("createdAt") LocalDateTime createdAt);

    /** 读取消息的图片：给定消息的全部图片行，按 message_id、sort_no 排序；messageIds 不能为空 */
    List<AiMessageImage> selectImagesByMessageIds(@Param("messageIds") Collection<Long> messageIds);

    /** 会话末尾 limit 条，id 倒序 */
    List<AiMessage> selectTail(@Param("sessionId") Long sessionId, @Param("limit") int limit);

    int deleteById(@Param("id") Long id);

    /** 上下文：同一会话中 id 小于 beforeId 的消息，id 倒序取 limit 条 */
    List<AiMessage> selectRecent(@Param("sessionId") Long sessionId,
                                 @Param("beforeId") Long beforeId,
                                 @Param("limit") int limit);

    /** 打开历史对话：会话的全部消息（含思考过程），id 正序 */
    List<AiMessage> selectBySession(@Param("sessionId") Long sessionId);

    /** 删除会话：该会话中带图提问的图片相对路径（t_ai_message_image 与 t_ai_message.image_url 两处，去重） */
    List<String> selectImageUrlsBySession(@Param("sessionId") Long sessionId);

    /** 删除会话：删除该会话全部消息的图片行（须在删除消息之前执行） */
    int deleteImagesBySession(@Param("sessionId") Long sessionId);

    /** 删除会话：删除该会话的全部消息 */
    int deleteBySession(@Param("sessionId") Long sessionId);

    /**
     * 清理图片前的核对（删除会话后、定期清理未发送的图片）：给定相对路径中仍被消息引用的部分
     * （t_ai_message_image 与 t_ai_message.image_url 任一处引用都算，去重）；urls 不能为空
     */
    List<String> selectImageUrlsInUse(@Param("urls") Collection<String> urls);

    /** AI 问答次数：该用户所有会话中 role 为 user 的消息数 */
    long countUserQuestions(@Param("userId") Long userId);

    /** 注销账号：该用户所有会话中带图提问的图片相对路径（t_ai_message_image 与 t_ai_message.image_url 两处，去重） */
    List<String> selectImageUrlsByUser(@Param("userId") Long userId);

    /** 注销账号：删除该用户所有会话消息的图片行（须在删除消息之前执行） */
    int deleteImagesByUser(@Param("userId") Long userId);

    /** 注销账号：删除该用户所有会话的消息（须在删除会话之前执行） */
    int deleteByUser(@Param("userId") Long userId);
}
