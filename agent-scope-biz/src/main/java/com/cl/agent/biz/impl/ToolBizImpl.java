package com.cl.agent.biz.impl;

import com.cl.agent.biz.IToolBiz;
import com.cl.agent.dto.ToolConfigResponse;
import com.cl.agent.model.ToolConfig;
import com.cl.agent.service.IToolConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具业务逻辑实现
 */
@Service
@Slf4j
public class ToolBizImpl implements IToolBiz {

    @Autowired
    private IToolConfigService toolConfigService;

    @Override
    public List<ToolConfigResponse> listAvailableTools() {
        return toolConfigService.listEnabled().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 实体转 DTO
     */
    private ToolConfigResponse toResponse(ToolConfig config) {
        ToolConfigResponse resp = new ToolConfigResponse();
        resp.setToolName(config.getToolName());
        resp.setDisplayName(config.getDisplayName());
        resp.setDescription(config.getDescription());
        resp.setCategory(config.getCategory());
        resp.setIcon(config.getIcon());
        resp.setEnabled(config.getEnabled());
        return resp;
    }
}
