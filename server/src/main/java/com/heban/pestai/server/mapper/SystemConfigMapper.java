package com.heban.pestai.server.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.heban.pestai.server.entity.SystemConfig;

/** t_system_config（SQL 见 resources/mapper/SystemConfigMapper.xml） */
@Mapper
public interface SystemConfigMapper {

    /** 全部后台配置（RuntimeConfigService 启动后首次使用时与每次修改后整体读入内存） */
    List<SystemConfig> selectAll();

    /** 新增或覆盖（按 config_key） */
    int upsert(SystemConfig config);

    /** 恢复为环境变量 / 默认值：删除该行 */
    int deleteByKey(@Param("configKey") String configKey);
}
