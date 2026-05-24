package com.cl.agent.tool.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 工具执行相关配置项，绑定 {@code agent.tool.*}。
 */
@Data
@ConfigurationProperties(prefix = "agent.tool")
public class AgentToolProperties {

    /** 是否启用 Agent 工具自动配置 */
    private boolean enabled = true;

    /** 单次工具执行超时上限（秒），超时后自动熔断并返回 Timeout Observation */
    private int executionTimeoutSeconds = 10;

    /** 新建 Agent 时默认启用的工具名称列表 */
    private List<String> defaultEnabled = new ArrayList<>(List.of(
            "get_weather",
            "calculate",
            "web_search"
    ));

    /** 内置工具包配置 */
    private Builtin builtin = new Builtin();

    /**
     * 内置工具包开关配置。
     */
    @Data
    public static class Builtin {

        /** 是否注册内置工具 Bean（weather / calculate / web_search） */
        private boolean enabled = true;
    }
}
