-- ============================================================
-- 3.3 智能记忆管理 (Memory Management) — 数据库变更脚本
-- 执行前请确保已连接到 agent_scope 数据库
-- ============================================================

-- 1. 为 t_agent_info 新增记忆相关字段
ALTER TABLE `t_agent_info`
    ADD COLUMN `memory_mode` VARCHAR(16) NOT NULL DEFAULT 'SUMMARY'
        COMMENT '记忆模式: FULL=全量不压缩 / WINDOW=纯滑动丢弃 / SUMMARY=摘要+滑动（默认）',
    ADD COLUMN `max_turns` INT DEFAULT NULL
        COMMENT '单 Agent 记忆窗口上限（轮），NULL 时使用全局默认值；FULL 模式下忽略此字段';

-- 2. 新增对话历史摘要表
CREATE TABLE `t_conversation_summary` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` VARCHAR(64)  NOT NULL                COMMENT '所属会话 ID',
    `summary`         TEXT         NOT NULL                COMMENT 'LLM 生成的历史摘要文本',
    `covered_up_to`   BIGINT       NOT NULL                COMMENT '本次摘要覆盖到的最后一条消息 ID',
    `create_by`       VARCHAR(64)  DEFAULT NULL            COMMENT '创建人',
    `create_time`     DATETIME     DEFAULT NULL            COMMENT '创建时间',
    `update_by`       VARCHAR(64)  DEFAULT NULL            COMMENT '更新人',
    `update_time`     DATETIME     DEFAULT NULL            COMMENT '更新时间',
    `del_flag`        INT          DEFAULT 0               COMMENT '删除状态 0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_conv_id_create_time` (`conversation_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话历史摘要表';
