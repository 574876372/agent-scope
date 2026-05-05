package com.cl.agent.service.impl;

import com.cl.agent.model.AgentInfo;
import com.cl.agent.service.IAgentService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentServiceImpl implements IAgentService {
    
    private final ConcurrentHashMap<String, AgentInfo> agentCache = new ConcurrentHashMap<>();

    @Override
    public void save(AgentInfo agentInfo) {
        agentCache.put(agentInfo.getId(), agentInfo);
    }

    @Override
    public AgentInfo getById(String id) {
        return agentCache.get(id);
    }

    @Override
    public List<AgentInfo> listAll() {
        return new ArrayList<>(agentCache.values());
    }

    @Override
    public void deleteById(String id) {
        agentCache.remove(id);
    }
}
