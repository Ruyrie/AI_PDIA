package com.heban.pestai.server.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.heban.pestai.server.entity.AppVersion;

/** t_app_version（预留） */
@Mapper
public interface AppVersionMapper {

    /** version_code 最大的一条 */
    AppVersion selectLatest();

    /** version_code 在 (fromExclusive, toInclusive] 之间且 force_update = 1 的行数 */
    int countForceBetween(@Param("fromExclusive") int fromExclusive, @Param("toInclusive") int toInclusive);
}
