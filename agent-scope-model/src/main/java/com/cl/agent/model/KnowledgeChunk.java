package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 知识库文档文本段落切片（Knowledge Chunk）明细实体类。
 * <p>对应 MySQL 中的 {@code t_knowledge_chunk} 表，继承自 {@link BaseEntity}。
 * 存储将原文档进行切片清洗后的纯文本段，用于关系库审计、前端召回展示回显及安全可读性溯源。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_knowledge_chunk")
public class KnowledgeChunk extends BaseEntity {

    /** 切片唯一标识符 ID */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属文档 ID */
    @TableField("doc_id")
    private String docId;

    /** 所属知识库 ID */
    @TableField("kb_id")
    private String kbId;

    /** 清洗切割后的纯文本内容 */
    @TableField("content")
    private String content;

    /** 文本切片在原文档中的物理顺序序号 (0-indexed) */
    @TableField("chunk_index")
    private Integer chunkIndex;

    /** 预估消耗/占用的 LLM Token 数量 */
    @TableField("token_count")
    private Integer tokenCount;
}
