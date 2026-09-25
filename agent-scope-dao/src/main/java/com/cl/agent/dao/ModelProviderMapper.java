package com.cl.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cl.agent.model.ModelProvider;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型厂商 Mapper，对应 {@code t_model_provider} 表。
 */
@Mapper
public interface ModelProviderMapper extends BaseMapper<ModelProvider> {
}
