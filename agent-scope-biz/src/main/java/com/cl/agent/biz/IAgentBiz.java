package com.cl.agent.biz;

import com.cl.agent.dto.AgentResponse;
import com.cl.agent.dto.ChatRequest;
import com.cl.agent.dto.ChatResponse;
import com.cl.agent.dto.CreateAgentRequest;
import io.agentscope.core.agent.Event;
import reactor.core.publisher.Flux;
import java.util.List;

/**
 * Agent 业务逻辑接口
 * 负责 Agent 的生命周期管理及对话能力编排
 */
public interface IAgentBiz {
    
    /**
     * 创建一个新的 Agent
     * 
     * @param request 创建请求参数
     * @return 创建成功的 Agent 信息
     */
    AgentResponse createAgent(CreateAgentRequest request);

    /**
     * 获取当前用户的所有 Agent 列表
     * 
     * @return Agent 列表
     */
    List<AgentResponse> listAgents();

    /**
     * 获取指定 ID 的 Agent 详情
     * 
     * @param id Agent ID
     * @return Agent 详情
     */
    AgentResponse getAgent(String id);

    /**
     * 删除指定 ID 的 Agent
     * 
     * @param id Agent ID
     */
    void deleteAgent(String id);

    /**
     * 更新 Agent 基本信息及工具绑定关系
     *
     * @param id      Agent ID
     * @param request 更新请求参数（复用 CreateAgentRequest）
     * @return 更新后的 Agent 信息
     */
    AgentResponse updateAgent(String id, CreateAgentRequest request);

    /**
     * 与指定的 Agent 进行对话
     * 
     * @param id Agent ID
     * @param request 对话请求内容
     * @return AI 的响应结果
     */
    ChatResponse chat(String id, ChatRequest request);

    /**
     * 以流式方式与指定 Agent 对话，推送推理、工具结果与最终回复等事件。
     *
     * @param id      Agent ID
     * @param request 对话请求内容
     * @return AgentScope {@link Event} 流
     */
    Flux<Event> chatStream(String id, ChatRequest request);
}
