package com.cl.agent.biz;

import com.cl.agent.dto.ToolConfigResponse;

import java.util.List;

/**
 * 工具业务逻辑接口
 * <p>提供工具列表查询等面向前端的业务操作。</p>
 */
public interface IToolBiz {

    /**
     * 获取所有可用（启用）的工具列表
     *
     * @return 工具配置响应列表
     */
    List<ToolConfigResponse> listAvailableTools();
}
