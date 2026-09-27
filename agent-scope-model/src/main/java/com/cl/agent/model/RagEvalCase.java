package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 知识库检索评估用例实体。
 * <p>对应 {@code t_rag_eval_case} 表：一个测试问题及其标准答案要点。运行评估时，
 * 要点在检索上下文中出现的比例即「召回率」，在模型回答中出现的比例即「答案完整度」。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_rag_eval_case")
public class RagEvalCase extends BaseEntity {

    /** 用例唯一标识符 ID */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属知识库 ID */
    @TableField("kb_id")
    private String kbId;

    /** 测试问题 */
    @TableField("question")
    private String question;

    /** 标准答案要点，每行一个，如字段名、错误码或关键结论 */
    @TableField("expected_points")
    private String expectedPoints;
}
