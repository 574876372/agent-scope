package com.cl.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cl.agent.model.AgentKbRel;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 与知识库多对多授权绑定数据访问 Mapper 接口。
 * <p>继承自 MyBatis Plus 的 {@link BaseMapper}，提供对 {@code t_agent_kb_rel} 表的通用 CRUD 持久化支持。</p>
 */
@Mapper
public interface AgentKbRelMapper extends BaseMapper<AgentKbRel> {
}
