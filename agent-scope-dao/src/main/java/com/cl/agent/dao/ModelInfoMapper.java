package com.cl.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cl.agent.model.ModelInfo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型定义 Mapper，对应 {@code t_model} 表。
 */
@Mapper
public interface ModelInfoMapper extends BaseMapper<ModelInfo> {
}
