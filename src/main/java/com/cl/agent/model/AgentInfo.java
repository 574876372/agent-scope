package com.cl.agent.model;

import io.agentscope.core.agent.Agent;
import java.time.LocalDateTime;

/**
 * Agent 实体，缓存在内存中
 */
public class AgentInfo {

    private String id;
    private String name;
    private String modelType;   // qwen / deepseek
    private String modelName;
    private String status;
    private String userId;
    private LocalDateTime createdAt;
    private String systemPrompt;

    /** 实际的 Agent 实例 */
    private transient Agent agent;

    public AgentInfo() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public Agent getAgent() { return agent; }
    public void setAgent(Agent agent) { this.agent = agent; }
}
