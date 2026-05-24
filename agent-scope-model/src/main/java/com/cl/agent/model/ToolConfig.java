package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 工具元数据配置实体类，对应 t_tool_config 表。
 * <p>应用启动时由 {@code ToolConfigSyncService} 自动将 {@code @AgentToolDef} 注解的元数据同步到此表。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_tool_config")
public class ToolConfig extends BaseEntity {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工具唯一名称（与 @AgentToolDef.name 对应，如 get_weather） */
    @TableField("tool_name")
    private String toolName;

    /** 工具展示名称（前端显示用，如 天气查询） */
    @TableField("display_name")
    private String displayName;

    /** 工具功能描述（供 LLM & 前端展示） */
    @TableField("description")
    private String description;

    /** 工具所在 Spring Bean 全类名 */
    @TableField("bean_class")
    private String beanClass;

    /** 被 @AgentToolDef 标注的方法名 */
    @TableField("method_name")
    private String methodName;

    /** 入参 JSON Schema */
    @TableField("parameters_schema")
    private String parametersSchema;

    /** 分类标签：builtin=内置 / custom=自定义 */
    @TableField("category")
    private String category;

    /** 展示图标（emoji 或 icon class） */
    @TableField("icon")
    private String icon;

    /** 是否全局启用（false=禁用，true=启用） */
    @TableField("enabled")
    private Boolean enabled;
}
