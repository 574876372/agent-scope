package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * Agent 与知识库多对多授权绑定关系实体类。
 * <p>对应 MySQL 中的 {@code t_agent_kb_rel} 表，继承自 {@link BaseEntity}。
 * 用以确立特定智能体 (Agent) 与一个或多个私有知识库的授权绑定拓扑结构，具备联合唯一约束。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_agent_kb_rel")
public class AgentKbRel extends BaseEntity {

    /** 关联主键唯一 ID */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 智能体唯一 ID */
    @TableField("agent_id")
    private String agentId;

    /** 关联绑定的知识库唯一 ID */
    @TableField("kb_id")
    private String kbId;
}
