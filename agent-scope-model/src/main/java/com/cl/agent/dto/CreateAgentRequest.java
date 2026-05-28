package com.cl.agent.dto;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

/**
 * 创建 Agent 请求参数
 */
@Data
public class CreateAgentRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Agent 名称 */
    private String name;

    /** 模型厂商类型，如 qwen（通义千问）/ deepseek 等 */
    private String modelType;

    /** 模型名称，如 qwen-max / deepseek-chat 等 */
    private String modelName;

    /** 系统提示词 */
    private String systemPrompt;

    /** 该 Agent 授权使用的工具名称列表（如 ["get_weather", "calculate"]） */
    private List<String> toolNames;

    /**
     * 记忆模式：FULL / WINDOW / SUMMARY。
     * null 时使用全局默认配置（SUMMARY）。
     */
    private String memoryMode;

    /**
     * 单 Agent 记忆窗口上限（轮数）。
     * null 时使用全局默认值；FULL 模式下忽略。
     */
    private Integer maxTurns;
}
