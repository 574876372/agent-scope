package com.cl.agent.service;

import com.cl.agent.model.ToolConfig;

import java.util.List;

/**
 * 工具元数据配置服务接口
 * <p>负责 {@code t_tool_config} 表的基础数据操作。</p>
 */
public interface IToolConfigService {

    /**
     * 查询所有全局启用的工具配置
     *
     * @return 启用的工具列表
     */
    List<ToolConfig> listEnabled();

    /**
     * 按工具名称查询配置
     *
     * @param toolName 工具唯一名称
     * @return 工具配置，不存在返回 null
     */
    ToolConfig getByToolName(String toolName);

    /**
     * 新增或更新工具配置（按 tool_name 做 UPSERT）
     *
     * @param config 工具配置实体
     */
    void saveOrUpdate(ToolConfig config);
}
