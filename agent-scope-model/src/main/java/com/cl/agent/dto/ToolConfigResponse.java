package com.cl.agent.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 工具配置响应 DTO，用于前端展示可用的工具列表。
 */
@Data
public class ToolConfigResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 工具唯一名称 */
    private String toolName;

    /** 工具展示名称 */
    private String displayName;

    /** 工具功能描述 */
    private String description;

    /** 分类标签：builtin / custom */
    private String category;

    /** 展示图标 */
    private String icon;

    /** 是否启用 */
    private Boolean enabled;

    /** 创建 Agent 时是否默认勾选；由 {@code agent.tool.default-enabled} 配置决定 */
    private Boolean defaultSelected;
}
