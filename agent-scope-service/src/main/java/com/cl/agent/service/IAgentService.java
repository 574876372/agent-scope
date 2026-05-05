package com.cl.agent.service;

import com.cl.agent.model.AgentInfo;
import java.util.List;

public interface IAgentService {
    void save(AgentInfo agentInfo);
    AgentInfo getById(String id);
    List<AgentInfo> listAll();
    void deleteById(String id);
}
