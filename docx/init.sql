-- 1. 用户表 (t_user)
CREATE TABLE `t_user` (
  `id` VARCHAR(64) NOT NULL COMMENT '用户唯一标识',
  `username` VARCHAR(64) NOT NULL COMMENT '用户名',
  `password` VARCHAR(255) NOT NULL COMMENT '密码',
  -- 基础类公共参数
  `create_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
  `create_time` DATETIME DEFAULT NULL COMMENT '创建时间',
  `update_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `del_flag` INT DEFAULT '0' COMMENT '删除状态 0 正常 1 删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. Agent 信息表 (t_agent_info)
CREATE TABLE `t_agent_info` (
  `id` VARCHAR(64) NOT NULL COMMENT '唯一标识符',
  `name` VARCHAR(128) DEFAULT NULL COMMENT 'Agent 名称',
  `model_type` VARCHAR(64) DEFAULT NULL COMMENT '模型厂商类型 (如: qwen, deepseek)',
  `model_name` VARCHAR(128) DEFAULT NULL COMMENT '具体模型名称',
  `status` VARCHAR(32) DEFAULT NULL COMMENT '状态 (如: active, deleted)',
  `user_id` VARCHAR(64) DEFAULT NULL COMMENT '所属用户 ID',
  `system_prompt` TEXT COMMENT '系统提示词',
  -- 基础类公共参数
  `create_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
  `create_time` DATETIME DEFAULT NULL COMMENT '创建时间',
  `update_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `del_flag` INT DEFAULT '0' COMMENT '删除状态 0 正常 1 删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 基础信息表';

-- 3. 会话表 (t_conversation)
CREATE TABLE `t_conversation` (
  `id` VARCHAR(64) NOT NULL COMMENT '会话唯一标识',
  `title` VARCHAR(255) DEFAULT NULL COMMENT '会话标题',
  `agent_id` VARCHAR(64) DEFAULT NULL COMMENT '关联的 Agent ID',
  `user_id` VARCHAR(64) DEFAULT NULL COMMENT '所属用户 ID',
  -- 基础类公共参数
  `create_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
  `create_time` DATETIME DEFAULT NULL COMMENT '创建时间',
  `update_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `del_flag` INT DEFAULT '0' COMMENT '删除状态 0 正常 1 删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

-- 4. 聊天消息表 (t_chat_message)
CREATE TABLE `t_chat_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息 ID',
  `role` VARCHAR(32) DEFAULT NULL COMMENT '角色: user / assistant',
  `content` TEXT COMMENT '消息内容',
  `timestamp` DATETIME DEFAULT NULL COMMENT '消息时间',
  `conversation_id` VARCHAR(64) DEFAULT NULL COMMENT '所属会话 ID',
  -- 基础类公共参数
  `create_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
  `create_time` DATETIME DEFAULT NULL COMMENT '创建时间',
  `update_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `del_flag` INT DEFAULT '0' COMMENT '删除状态 0 正常 1 删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单条聊天消息表';
