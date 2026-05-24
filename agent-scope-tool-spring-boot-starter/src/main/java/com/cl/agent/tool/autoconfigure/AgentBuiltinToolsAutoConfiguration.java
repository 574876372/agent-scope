package com.cl.agent.tool.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

/**
 * 内置工具包自动配置：扫描 {@code com.cl.agent.tool.builtin} 及其子包下的 {@code @Component} 工具实现。
 * <p>约定：接口定义在 {@code com.cl.agent.tool.builtin}，实现类放在 {@code impl} 子包。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "agent.tool.builtin", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan(basePackages = "com.cl.agent.tool.builtin")
public class AgentBuiltinToolsAutoConfiguration {
}
