package com.heban.pestai.server.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.entity.RecognitionStats;

/** t_recognition（按用户的查询都带 user_id 与 deleted = 0 条件；注销账号用的两个方法除外，它们包含已逻辑删除的记录） */
@Mapper
public interface RecognitionMapper {

    /** 插入识别记录，回填自增 id */
    int insert(Recognition recognition);

    /** 首页最近识别：只取列表需要的 9 列，created_at 倒序、id 倒序 */
    List<Recognition> selectRecent(@Param("userId") Long userId, @Param("size") int size);

    long countByUser(@Param("userId") Long userId, @Param("mode") String mode);

    List<Recognition> selectPageByUser(@Param("userId") Long userId,
                                       @Param("mode") String mode,
                                       @Param("offset") long offset,
                                       @Param("size") int size);

    /** 详情：id、user_id 匹配且 deleted = 0 */
    Recognition selectByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /** 归属查询：按主键读取 user_id、deleted、status、top_name（不带 deleted 条件） */
    Recognition selectOwnerById(@Param("id") Long id);

    /** 逻辑删除：条件 id、user_id 且 deleted = 0 */
    int logicDelete(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 重新识别成功：覆盖识别字段，fail_reason 置空；条件 id、user_id、deleted = 0 */
    int updateResult(Recognition recognition);

    /** 重新识别失败（原记录本身是失败记录）：只更新 status、fail_reason */
    int updateFailure(@Param("id") Long id,
                      @Param("userId") Long userId,
                      @Param("status") String status,
                      @Param("failReason") String failReason,
                      @Param("now") LocalDateTime now);

    /** 我的页统计：total、pest_kinds、month_count 一次聚合 */
    RecognitionStats selectStatCounts(@Param("userId") Long userId,
                                      @Param("monthStart") LocalDateTime monthStart,
                                      @Param("nextMonthStart") LocalDateTime nextMonthStart);

    /** 注销账号：该用户全部识别照片的相对路径（含已逻辑删除的记录，去重） */
    List<String> selectAllImageUrlsByUser(@Param("userId") Long userId);

    /** 注销账号：物理删除该用户的全部识别记录（含已逻辑删除的记录） */
    int deleteAllByUser(@Param("userId") Long userId);
}
