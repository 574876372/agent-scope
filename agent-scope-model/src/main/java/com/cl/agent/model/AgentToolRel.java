package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * Agent 与工具的多对多关联实体，对应 t_agent_tool_rel 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_agent_tool_rel")
public class AgentToolRel extends BaseEntity {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的 Agent ID */
    @TableField("agent_id")
    private String agentId;

    /** 关联的工具名称（对应 t_tool_config.tool_name） */
    @TableField("tool_name")
    private String toolName;
}
