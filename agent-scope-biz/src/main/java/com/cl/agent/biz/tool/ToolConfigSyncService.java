package com.cl.agent.biz.tool;

import com.cl.agent.model.ToolConfig;
import com.cl.agent.service.IToolConfigService;
import com.cl.agent.tool.core.ReflectiveAgentTool;
import com.cl.agent.tool.core.ToolRegistrySyncCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;

/**
 * 工具元数据同步服务：应用启动时自动将 {@code @AgentToolDef} 注解的工具信息同步到 {@code t_tool_config} 表。
 * <p>实现 {@link ToolRegistrySyncCallback}，在 {@link com.cl.agent.tool.core.AgentToolRegistry}
 * 完成扫描后被调用。</p>
 */
@Service
@Slf4j
public class ToolConfigSyncService implements ToolRegistrySyncCallback {

    /** 内置工具名称与展示信息的映射 */
    private static final Map<String, String[]> BUILTIN_DISPLAY_MAP = Map.of(
            "get_weather", new String[]{"天气查询", "🌤️"},
            "calculate", new String[]{"数学计算", "🔢"},
            "web_search", new String[]{"网页搜索", "🔍"}
    );

    @Autowired
    private IToolConfigService toolConfigService;

    /**
     * 将已注册的 ReflectiveAgentTool 元数据同步到数据库。
     *
     * @param tools 已注册的全部工具集合
     */
    @Override
    public void onToolsRegistered(Collection<ReflectiveAgentTool> tools) {
        int count = 0;
        for (ReflectiveAgentTool tool : tools) {
            ToolConfig config = buildToolConfig(tool);
            toolConfigService.saveOrUpdate(config);
            count++;
        }
        log.info("[ToolSync] 已同步 {} 条工具元数据到数据库", count);
    }

    /**
     * 从 ReflectiveAgentTool 提取注解元数据构建 ToolConfig 实体。
     */
    private ToolConfig buildToolConfig(ReflectiveAgentTool tool) {
        String toolName = tool.getName();
        String[] displayInfo = BUILTIN_DISPLAY_MAP.get(toolName);

        return ToolConfig.builder()
                .toolName(toolName)
                .displayName(displayInfo != null ? displayInfo[0] : toolName)
                .description(tool.getDescription())
                .beanClass(tool.getBean().getClass().getName())
                .methodName(tool.getToolMethod().getName())
                .parametersSchema(tool.getMetadata().parametersSchema())
                .category(BUILTIN_DISPLAY_MAP.containsKey(toolName) ? "builtin" : "custom")
                .icon(displayInfo != null ? displayInfo[1] : "🔧")
                .enabled(true)
                .build();
    }
}
