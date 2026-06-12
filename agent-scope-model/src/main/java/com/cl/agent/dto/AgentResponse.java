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
     * 单 Agent 专属检索最大召回数量（分片数）。
     */
    private Integer recallLimit;

    /**
     * 单 Agent 专属检索最低相似度得分过滤阈值。
     */
    private Double scoreThreshold;

    /**
     * 该 Agent 绑定的关联知识库唯一标识符 ID 列表。
     */
    private List<String> kbIds;
}
