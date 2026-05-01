package com.cl.agent.service;

import com.cl.agent.dto.AgentResponse;
import com.cl.agent.dto.ChatRequest;
import com.cl.agent.dto.ChatResponse;
import com.cl.agent.dto.CreateAgentRequest;

import java.util.List;

/**
 * Agent 业务接口
 */
public interface IAgentService {

    /**
     * 创建并缓存一个 Agent
     */
    AgentResponse createAgent(CreateAgentRequest request);

    /**
     * 获取所有已创建的 Agent
     */
    List<AgentResponse> listAgents();

    /**
     * 获取单个 Agent 信息
     */
    AgentResponse getAgent(String id);

    /**
     * 删除 Agent
     */
    void deleteAgent(String id);

    /**
     * 向指定 Agent 发送消息并获取回复
     */
    ChatResponse chat(String id, ChatRequest request);
}
