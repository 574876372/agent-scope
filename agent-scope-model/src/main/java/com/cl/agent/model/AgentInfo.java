package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
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
     * 最终交给模型的段数（Top-K，上下文扩展与合并后的连续原文段）。
     * <p>null 时使用 agent.rag.retrieval.final-top-k。</p>
     */
    @TableField(value = "recall_limit", updateStrategy = FieldStrategy.ALWAYS)
    private Integer recallLimit;

    /**
     * 向量召回预过滤阈值：低于该相似度的向量结果不参与融合。
     * <p>取值范围 0.0 ~ 1.0，null 时使用 agent.rag.default-score-threshold。</p>
     */
    @TableField(value = "score_threshold", updateStrategy = FieldStrategy.ALWAYS)
    private Double scoreThreshold;

    /**
     * 是否结合对话历史改写检索词。
     * <p>null 时使用 agent.rag.retrieval.query-rewrite。</p>
     */
    @TableField(value = "query_rewrite", updateStrategy = FieldStrategy.ALWAYS)
    private Boolean queryRewrite;

    /**
     * 注入上下文总字符数上限。
     * <p>null 时使用 agent.rag.retrieval.context-max-chars。</p>
     */
    @TableField(value = "context_max_chars", updateStrategy = FieldStrategy.ALWAYS)
    private Integer contextMaxChars;

    /**
     * 重排模型 ID，关联 t_model.id（RERANK 类型）。
     * <p>null 时使用默认重排模型；未配置默认重排模型则不重排。</p>
     */
    @TableField(value = "rerank_model_id", updateStrategy = FieldStrategy.ALWAYS)
    private String rerankModelId;
}
