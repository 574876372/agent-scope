package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.*;

/**
 * Agent 基础信息实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_agent_info")
public class AgentInfo extends BaseEntity {

    /** 唯一标识符 */
    @TableId(type = IdType.INPUT)
    private String id;
    
    /** Agent 名称 */
    @TableField("name")
    private String name;
    
    /** 模型厂商类型 (如: qwen, deepseek) */
    @TableField("model_type")
    private String modelType;
    
    /** 具体模型名称 */
    @TableField("model_name")
    private String modelName;
    
    /** 状态 (如: active, deleted) */
    @TableField("status")
    private String status;
    
    /** 所属用户 ID */
    @TableField("user_id")
    private String userId;
    
    /** 系统提示词 (System Prompt) */
    @TableField("system_prompt")
    private String systemPrompt;

    /**
     * 记忆模式：FULL=全量不压缩 / WINDOW=纯滑动丢弃 / SUMMARY=摘要+滑动（默认）。
     * 对应枚举 {@link com.cl.agent.enums.MemoryMode}，以字符串形式存储。
     */
    @TableField("memory_mode")
    private String memoryMode;

    /**
     * 记忆窗口上限（轮数）。
     * null 时使用全局默认值（由 AgentMemoryProperties 配置）；FULL 模式下忽略此字段。
     */
    @TableField("max_turns")
    private Integer maxTurns;

    /**
     * RAG 检索驱动模式：DISABLED=禁用 / GENERIC=通用前置 / AGENTIC=智能体自主。
     * <p>对应枚举 RAGMode，以字符串形式存储。</p>
     */
    @TableField("rag_mode")
    private String ragMode;

    /**
     * 单 Agent 专属检索最大召回数量（分片数）。
     * <p>null 时使用系统默认配置（在 agent.rag.defaultRowLimit 中）。</p>
     */
    @TableField("recall_limit")
    private Integer recallLimit;

    /**
     * 单 Agent 专属检索最低相似度得分过滤阈值。
     * <p>取值范围 0.0 ~ 1.0，null 时使用系统默认配置（在 agent.rag.defaultScoreThreshold 中）。</p>
     */
    @TableField("score_threshold")
    private Double scoreThreshold;
}
