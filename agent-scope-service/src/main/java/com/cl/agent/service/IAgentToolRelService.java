package com.cl.agent.service;

import java.util.List;

/**
 * Agent-工具关联服务接口
 * <p>负责 {@code t_agent_tool_rel} 表的关联数据操作。</p>
 */
public interface IAgentToolRelService {

    /**
     * 查询指定 Agent 关联的工具名称列表
     *
     * @param agentId Agent ID
     * @return 工具名称列表
     */
    List<String> getToolNamesByAgentId(String agentId);

    /**
     * 全量替换指定 Agent 的工具关联（先删后插）
     *
     * @param agentId   Agent ID
     * @param toolNames 工具名称列表
     */
    void replaceToolsForAgent(String agentId, List<String> toolNames);

    /**
     * 删除指定 Agent 的所有工具关联（级联清理）
     *
     * @param agentId Agent ID
     */
    void deleteByAgentId(String agentId);
}
