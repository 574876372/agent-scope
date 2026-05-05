package com.cl.agent.service.impl;

import com.cl.agent.model.AgentInfo;
import com.cl.agent.service.IAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 基础数据服务实现类
 * 当前版本使用内存 ConcurrentHashMap 进行数据存储，后续可扩展为数据库存储
 */
@Service
@Slf4j
public class AgentServiceImpl implements IAgentService {
    
    /** 内存缓存：Agent ID -> AgentInfo */
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
