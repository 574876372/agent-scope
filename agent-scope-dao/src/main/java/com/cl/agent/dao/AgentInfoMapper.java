package com.cl.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cl.agent.model.AgentInfo;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 信息 Mapper 接口
 */
@Mapper
public interface AgentInfoMapper extends BaseMapper<AgentInfo> {
}
