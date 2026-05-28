-- 5. 工具元数据表 (t_tool_config)
-- 存储系统中所有可用的 Agent 工具信息，应用启动时由 ToolConfigSyncService 自动同步
CREATE TABLE `t_tool_config` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tool_name`         VARCHAR(128) NOT NULL COMMENT '工具唯一名称（与 @AgentToolDef.name 对应，如 get_weather）',
  `display_name`      VARCHAR(128) DEFAULT NULL COMMENT '工具展示名称（前端显示用，如 天气查询）',
  `description`       VARCHAR(512) DEFAULT NULL COMMENT '工具功能描述（供 LLM & 前端展示）',
  `bean_class`        VARCHAR(255) DEFAULT NULL COMMENT '工具所在 Spring Bean 全类名',
  `method_name`       VARCHAR(128) DEFAULT NULL COMMENT '被 @AgentToolDef 标注的方法名',
  `parameters_schema` TEXT         DEFAULT NULL COMMENT '入参 JSON Schema',
  `category`          VARCHAR(64)  DEFAULT 'builtin' COMMENT '分类标签：builtin=内置 / custom=自定义',
  `icon`              VARCHAR(64)  DEFAULT '🔧' COMMENT '展示图标（emoji 或 icon class）',
  `enabled`           TINYINT(1)   DEFAULT 1 COMMENT '是否全局启用（0=禁用，1=启用）',
  -- 审计公共字段
  `create_by`   VARCHAR(64) DEFAULT NULL COMMENT '创建人',
  `create_time` DATETIME    DEFAULT NULL COMMENT '创建时间',
  `update_by`   VARCHAR(64) DEFAULT NULL COMMENT '更新人',
  `update_time` DATETIME    DEFAULT NULL COMMENT '更新时间',
  `del_flag`    INT         DEFAULT 0    COMMENT '删除标志 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_name` (`tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具元数据配置表';

-- 6. Agent-工具关联表 (t_agent_tool_rel)
-- 实现 Agent 与工具的多对多关联，由 AgentBizImpl 在创建/更新 Agent 时维护
CREATE TABLE `t_agent_tool_rel` (
  `id`        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id`  VARCHAR(64)  NOT NULL COMMENT '关联的 Agent ID',
  `tool_name` VARCHAR(128) NOT NULL COMMENT '关联的工具名称（对应 t_tool_config.tool_name）',
  -- 审计公共字段
  `create_by`   VARCHAR(64) DEFAULT NULL COMMENT '创建人',
  `create_time` DATETIME    DEFAULT NULL COMMENT '创建时间',
  `update_by`   VARCHAR(64) DEFAULT NULL COMMENT '更新人',
  `update_time` DATETIME    DEFAULT NULL COMMENT '更新时间',
  `del_flag`    INT         DEFAULT 0    COMMENT '删除标志 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_tool` (`agent_id`, `tool_name`),
  KEY `idx_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 与工具的多对多关联表';
