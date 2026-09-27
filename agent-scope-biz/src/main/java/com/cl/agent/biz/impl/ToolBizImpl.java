package com.cl.agent.biz.impl;

import com.cl.agent.biz.IToolBiz;
import com.cl.agent.dto.ToolConfigResponse;
import com.cl.agent.model.ToolConfig;
import com.cl.agent.service.IToolConfigService;
import com.cl.agent.tool.core.AgentToolProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具业务逻辑实现
 */
@Service
@Slf4j
public class ToolBizImpl implements IToolBiz {

    @Autowired
    private IToolConfigService toolConfigService;

    /** 工具模块配置，提供新建 Agent 时默认勾选的工具清单；工具模块关闭时为 null */
    @Autowired(required = false)
    private AgentToolProperties toolProperties;

    /**
     * 列出所有已启用的工具，并标记创建 Agent 时是否默认勾选。
     * <p>使用说明：供前端创建 / 编辑 Agent 时渲染工具选择卡片；只有出现在 {@code agent.tool.default-enabled}
     * 中的工具 {@code defaultSelected=true}，避免把 SQL 等不需要的工具默认挂给新 Agent。</p>
     *
     * @return 已启用工具列表；无工具时返回空列表
     */
    @Override
    public List<ToolConfigResponse> listAvailableTools() {
        // 默认勾选的工具名集合；未配置时为空集合，即全部不勾选
        Set<String> defaults = toolProperties != null && toolProperties.getDefaultEnabled() != null
                ? new HashSet<>(toolProperties.getDefaultEnabled())
                : Set.of();
        return toolConfigService.listEnabled().stream()
                .map(this::toResponse)
                .peek(resp -> resp.setDefaultSelected(defaults.contains(resp.getToolName())))
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
