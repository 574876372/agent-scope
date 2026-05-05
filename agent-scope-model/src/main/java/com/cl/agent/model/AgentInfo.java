package com.cl.agent.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.LocalDateTime;

/**
 * Agent 基础信息实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentInfo {

    /** 唯一标识符 */
    private String id;
    
    /** Agent 名称 */
    private String name;
    
    /** 模型厂商类型 (如: qwen, deepseek) */
    private String modelType;
    
    /** 具体模型名称 */
    private String modelName;
    
    /** 状态 (如: active, deleted) */
    private String status;
    
    /** 所属用户 ID */
    private String userId;
    
    /** 创建时间 */
    private LocalDateTime createdAt;
    
    /** 系统提示词 (System Prompt) */
    private String systemPrompt;
}
