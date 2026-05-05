package com.cl.agent.dto;

import java.io.Serializable;

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
}
