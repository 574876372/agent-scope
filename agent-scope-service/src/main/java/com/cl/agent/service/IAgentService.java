package com.cl.agent.service;

import com.cl.agent.model.AgentInfo;
import java.util.List;

/**
 * Agent 基础数据服务接口
 * 负责 Agent 元数据的持久化操作 (CRUD)
 */
public interface IAgentService {
    
    /**
     * 保存或更新 Agent 信息
     * 
     * @param agentInfo Agent 实体对象
     */
    void save(AgentInfo agentInfo);

    /**
     * 根据 ID 获取 Agent 信息
     * 
     * @param id Agent ID
     * @return Agent 实体对象，不存在则返回 null
     */
    AgentInfo getById(String id);

    /**
     * 获取所有 Agent 列表
     * 
     * @return 所有 Agent 实体列表
     */
    List<AgentInfo> listAll();

    /**
     * 根据 ID 删除 Agent 记录
     * 
     * @param id Agent ID
     */
    void deleteById(String id);
}
