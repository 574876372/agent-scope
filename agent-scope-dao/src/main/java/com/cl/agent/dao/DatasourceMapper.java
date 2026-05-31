package com.cl.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cl.agent.model.Datasource;
import org.apache.ibatis.annotations.Mapper;

/**
 * 外部业务数据源 Mapper。
 *
 * <p>对应 {@code t_datasource} 表；多租户隔离由上层 Service 在 QueryWrapper 中追加
 * {@code .eq("user_id", UserContext.getUserId())} 实现。</p>
 */
@Mapper
public interface DatasourceMapper extends BaseMapper<Datasource> {
}
