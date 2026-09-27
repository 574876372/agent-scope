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

    /**
     * RAG 检索配置模式：DISABLED=禁用 / GENERIC=通用前置 / AGENTIC=智能体自主。
     * <p>对应 RAGMode 标识，以字符串形式传递。</p>
     */
    private String ragMode;

    /**
     * 最终交给模型的段数（Top-K）。
     * <p>null 时使用 agent.rag.retrieval.final-top-k。</p>
     */
    private Integer recallLimit;

    /**
     * 向量召回预过滤阈值，取值范围 0.0 ~ 1.0。
     * <p>null 时使用 agent.rag.default-score-threshold。</p>
     */
    private Double scoreThreshold;

    /**
     * 是否结合对话历史改写检索词；null 时使用全局默认。
     */
    private Boolean queryRewrite;

    /**
     * 注入上下文总字符数上限；null 时使用全局默认。
     */
    private Integer contextMaxChars;

    /**
     * 重排模型 ID（RERANK 类型）；null 或空串时使用默认重排模型，未配置则不重排。
     */
    private String rerankModelId;

    /**
     * 绑定的关联知识库唯一标识符 ID 列表。
     */
    private List<String> kbIds;
}
