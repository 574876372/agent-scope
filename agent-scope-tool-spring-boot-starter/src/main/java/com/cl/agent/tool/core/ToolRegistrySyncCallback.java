package com.cl.agent.tool.core;

import java.util.Collection;

/**
 * 工具注册完成后的同步回调接口。
 * <p>实现此接口可在 {@link AgentToolRegistry} 完成扫描后，将工具元数据同步到外部存储（如数据库）。</p>
 */
public interface ToolRegistrySyncCallback {

    /**
     * 当所有 {@link ReflectiveAgentTool} 注册完成后被调用。
     *
     * @param tools 已注册的全部工具集合
     */
    void onToolsRegistered(Collection<ReflectiveAgentTool> tools);
}
