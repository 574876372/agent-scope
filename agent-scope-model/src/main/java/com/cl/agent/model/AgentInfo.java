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
}
