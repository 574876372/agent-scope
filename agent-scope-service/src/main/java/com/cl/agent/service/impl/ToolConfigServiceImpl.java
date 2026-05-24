package com.cl.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cl.agent.dao.ToolConfigMapper;
import com.cl.agent.model.ToolConfig;
import com.cl.agent.service.IToolConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工具元数据配置服务实现
 */
@Service
@Slf4j
public class ToolConfigServiceImpl implements IToolConfigService {

    @Autowired
    private ToolConfigMapper toolConfigMapper;

    @Override
    public List<ToolConfig> listEnabled() {
        LambdaQueryWrapper<ToolConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ToolConfig::getEnabled, true);
        return toolConfigMapper.selectList(wrapper);
    }

    @Override
    public ToolConfig getByToolName(String toolName) {
        LambdaQueryWrapper<ToolConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ToolConfig::getToolName, toolName);
        return toolConfigMapper.selectOne(wrapper);
    }

    @Override
    public void saveOrUpdate(ToolConfig config) {
        ToolConfig existing = getByToolName(config.getToolName());
        if (existing != null) {
            config.setId(existing.getId());
            toolConfigMapper.updateById(config);
            log.debug("[ToolConfig] 更新工具配置: {}", config.getToolName());
        } else {
            toolConfigMapper.insert(config);
            log.debug("[ToolConfig] 新增工具配置: {}", config.getToolName());
        }
    }
}
