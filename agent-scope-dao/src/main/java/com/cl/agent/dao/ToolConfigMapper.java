package com.cl.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cl.agent.model.ToolConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具元数据配置 Mapper 接口
 */
@Mapper
public interface ToolConfigMapper extends BaseMapper<ToolConfig> {
}
