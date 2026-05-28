package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 对话历史摘要实体
 * <p>当会话轮数超过 Agent 配置的 maxTurns 阈值，且记忆模式为 SUMMARY 时，
 * 由 {@code MemoryManager} 调用 LLM 生成摘要并持久化至此表。</p>
 * <p>设计要点：
 * <ul>
 *   <li>{@code coveredUpTo}：摘要覆盖到的最后一条 t_chat_message 的 id，用于增量摘要时过滤已处理消息。</li>
 *   <li>原始消息不删除，摘要作为补充索引存在，保留完整审计链路。</li>
 * </ul>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_conversation_summary")
public class ConversationSummary extends BaseEntity {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID */
    @TableField("conversation_id")
    private String conversationId;

    /** LLM 生成的历史摘要文本 */
    @TableField("summary")
    private String summary;

    /**
     * 本次摘要覆盖到的最后一条消息 ID（对应 t_chat_message.id）。
     * 下次摘要时只处理 id 大于此值的消息，避免重复摘要。
     */
    @TableField("covered_up_to")
    private Long coveredUpTo;
}
