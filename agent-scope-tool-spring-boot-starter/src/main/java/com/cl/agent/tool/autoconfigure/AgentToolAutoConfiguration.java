package com.cl.agent.tool.autoconfigure;

import com.cl.agent.tool.core.AgentToolProperties;
import com.cl.agent.tool.core.AgentToolRegistry;
import com.cl.agent.tool.core.AgentToolkitFactory;
import com.cl.agent.tool.core.ToolRegistrySyncCallback;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Optional;

/**
 * Agent 工具框架自动配置：注册工具扫描器与 Toolkit 工厂。
 */
@AutoConfiguration
@AutoConfigureAfter(AgentBuiltinToolsAutoConfiguration.class)
@ConditionalOnClass(Toolkit.class)
@ConditionalOnProperty(prefix = "agent.tool", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AgentToolProperties.class)
public class AgentToolAutoConfiguration {

    /**
     * 注册工具扫描器 Bean。
     *
     * @param beanFactory  Spring Bean 工厂
     * @param syncCallback 可选的同步回调（由 biz 层提供实现）
     * @return 工具注册表
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentToolRegistry agentToolRegistry(ListableBeanFactory beanFactory,
                                               Optional<ToolRegistrySyncCallback> syncCallback) {
        return new AgentToolRegistry(beanFactory, syncCallback);
    }

    /**
     * 注册 Toolkit 构建工厂 Bean。
     *
     * @param toolRegistry   工具注册表
     * @param toolProperties 工具配置
     * @return Toolkit 工厂
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentToolkitFactory agentToolkitFactory(AgentToolRegistry toolRegistry,
                                                   AgentToolProperties toolProperties) {
        return new AgentToolkitFactory(toolRegistry, toolProperties);
    }
}
