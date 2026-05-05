package com.cl.agent.biz;

import com.cl.agent.dto.AgentResponse;
import com.cl.agent.dto.ChatRequest;
import com.cl.agent.dto.ChatResponse;
import com.cl.agent.dto.CreateAgentRequest;
import java.util.List;

public interface IAgentBiz {
    AgentResponse createAgent(CreateAgentRequest request);
    List<AgentResponse> listAgents();
    AgentResponse getAgent(String id);
    void deleteAgent(String id);
    ChatResponse chat(String id, ChatRequest request);
}
