package com.cl.agent.tool.core;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;

/**
 * 根据 Agent 关联的工具名称构建带超时熔断的 AgentScope {@link Toolkit}。
 */
@Slf4j
public class AgentToolkitFactory {

    /** 工具注册表 */
    private final AgentToolRegistry toolRegistry;

    /** 工具执行配置 */
    private final AgentToolProperties toolProperties;

    /**
     * 构造 Toolkit 工厂。
     *
     * @param toolRegistry   工具注册表
     * @param toolProperties 工具配置
     */
    public AgentToolkitFactory(AgentToolRegistry toolRegistry, AgentToolProperties toolProperties) {
        this.toolRegistry = toolRegistry;
        this.toolProperties = toolProperties;
    }

    /**
     * 为 Agent 构建 Toolkit，注入指定工具并设置执行超时。
     * <p>当 {@code toolNames} 为空时，使用配置中的 {@link AgentToolProperties#getDefaultEnabled()}。</p>
     *
     * @param toolNames 该 Agent 授权使用的工具名称列表，null 时使用默认工具列表
     * @return 配置完成的 Toolkit；若无可用工具则返回 null
     */
    public Toolkit createToolkit(List<String> toolNames) {
        List<String> resolvedNames = (toolNames == null || toolNames.isEmpty())
                ? toolProperties.getDefaultEnabled()
                : toolNames;

        List<ReflectiveAgentTool> tools = toolRegistry.resolveTools(resolvedNames);
        if (tools.isEmpty()) {
            log.info("[Tool] 无可用工具，跳过 Toolkit 注入");
            return null;
        }

        Duration timeout = Duration.ofSeconds(toolProperties.getExecutionTimeoutSeconds());
        ToolkitConfig config = ToolkitConfig.builder()
                .executionConfig(ExecutionConfig.builder()
                        .timeout(timeout)
                        .maxAttempts(1)
                        .build())
                .build();
        Toolkit toolkit = new Toolkit(config);

        for (ReflectiveAgentTool tool : tools) {
            toolkit.registerAgentTool(tool);
        }

        log.info("[Tool] 已构建 Toolkit，工具={}, 超时={}s", resolvedNames, toolProperties.getExecutionTimeoutSeconds());
        return toolkit;
    }
}
