package com.cl.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cl.agent.dao.AgentToolRelMapper;
import com.cl.agent.model.AgentToolRel;
import com.cl.agent.service.IAgentToolRelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent-工具关联服务实现
 */
@Service
@Slf4j
public class AgentToolRelServiceImpl implements IAgentToolRelService {

    @Autowired
    private AgentToolRelMapper agentToolRelMapper;

    @Override
    public List<String> getToolNamesByAgentId(String agentId) {
        LambdaQueryWrapper<AgentToolRel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentToolRel::getAgentId, agentId);
        return agentToolRelMapper.selectList(wrapper).stream()
                .map(AgentToolRel::getToolName)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceToolsForAgent(String agentId, List<String> toolNames) {
        // 先删除旧关联
        deleteByAgentId(agentId);
        // 批量插入新关联
        if (toolNames != null && !toolNames.isEmpty()) {
            for (String toolName : toolNames) {
                AgentToolRel rel = AgentToolRel.builder()
                        .agentId(agentId)
                        .toolName(toolName)
                        .build();
                agentToolRelMapper.insert(rel);
            }
            log.info("[AgentToolRel] Agent {} 关联 {} 个工具: {}", agentId, toolNames.size(), toolNames);
        }
    }

    @Override
    public void deleteByAgentId(String agentId) {
        LambdaQueryWrapper<AgentToolRel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentToolRel::getAgentId, agentId);
        agentToolRelMapper.delete(wrapper);
    }
}
