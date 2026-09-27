package com.cl.agent.dto;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * Agent 响应 DTO
 * 用于返回 Agent 的详细信息
 */
@Data
public class AgentResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Agent 唯一标识 */
    private String id;

    /** Agent 名称 */
    private String name;

    /** 模型类型，如 qwen / deepseek */
    private String modelType;

    /** 实际使用的模型名称 */
    private String modelName;

    /** Agent 当前状态，如 RUNNING / STOPPED */
    private String status;

    /** Agent 创建时间 */
    private LocalDateTime createTime;

    /** 系统提示词 */
    private String systemPrompt;

    /** 该 Agent 关联的工具名称列表 */
    private List<String> toolNames;

    /**
     * 记忆模式：FULL / WINDOW / SUMMARY，供前端展示和编辑时回显。
     */
    private String memoryMode;

    /**
     * 记忆窗口上限（轮数），供前端展示和编辑时回显。
     * null 表示使用全局默认值。
     */
    private Integer maxTurns;

    /**
     * RAG 检索配置模式：DISABLED=禁用 / GENERIC=通用前置 / AGENTIC=智能体自主。
     */
    private String ragMode;

    /**
     * 最终交给模型的段数（Top-K）；null 表示使用全局默认。
     */
    private Integer recallLimit;

    /**
     * 向量召回预过滤阈值；null 表示使用全局默认。
     */
    private Double scoreThreshold;

    /**
     * 是否结合对话历史改写检索词；null 表示使用全局默认。
     */
    private Boolean queryRewrite;

    /**
     * 注入上下文总字符数上限；null 表示使用全局默认。
     */
    private Integer contextMaxChars;

    /**
     * 重排模型 ID；null 表示使用默认重排模型。
     */
    private String rerankModelId;

    /**
     * 该 Agent 绑定的关联知识库唯一标识符 ID 列表。
     */
    private List<String> kbIds;
}
