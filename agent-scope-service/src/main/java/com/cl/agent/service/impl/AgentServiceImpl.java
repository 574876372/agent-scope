package com.cl.agent.service.impl;

import com.cl.agent.dao.AgentInfoMapper;
import com.cl.agent.model.AgentInfo;
import com.cl.agent.service.IAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 基础数据服务实现类
 */
@Service
@Slf4j
public class AgentServiceImpl implements IAgentService {
    
    @Autowired
    private AgentInfoMapper agentInfoMapper;

    @Override
    public void save(AgentInfo agentInfo) {
        log.debug("Saving agent: {}", agentInfo.getName());
        if (agentInfoMapper.selectById(agentInfo.getId()) != null) {
            agentInfoMapper.updateById(agentInfo);
        } else {
            agentInfoMapper.insert(agentInfo);
        }
    }

    @Override
    public AgentInfo getById(String id) {
        log.debug("Getting agent by id: {}", id);
        return agentInfoMapper.selectById(id);
    }

    @Override
    public List<AgentInfo> listAll() {
        log.debug("Listing all agents");
        return agentInfoMapper.selectList(null);
    }

    @Override
    public void deleteById(String id) {
        log.debug("Deleting agent by id: {}", id);
        agentInfoMapper.deleteById(id);
    }
}
