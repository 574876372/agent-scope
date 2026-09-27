-- ============================================================
-- 3.5 SQL Agent (多数据源 + HITL 确认) — 数据库变更脚本
-- 模块: agent-scope-sql-spring-boot-starter + agent-scope-biz/sql
-- 用途: 注册外部业务数据源 (t_datasource) 与 SQL 执行审计 (t_sql_audit)
-- 执行前请确保已连接到 agent_scope 数据库
-- ============================================================

-- 1. t_datasource: 用户注册的外部业务数据源 (LLM 通过 list_datasources 工具可见)
CREATE TABLE `t_datasource` (
    `id`              VARCHAR(64)  NOT NULL                COMMENT '主键 UUID',
    `user_id`         VARCHAR(64)  NOT NULL                COMMENT '所属用户 ID (多租户隔离, 与 t_user.id 对齐)',
    `name`            VARCHAR(128) NOT NULL                COMMENT '展示名称, 如「线上订单库」',
    `description`     VARCHAR(512) DEFAULT NULL            COMMENT '用途描述, 是 LLM 选择 datasourceId 的关键依据',
    `db_type`         VARCHAR(32)  NOT NULL DEFAULT 'mysql' COMMENT '数据库类型: mysql / postgres (首期仅 mysql)',
    `jdbc_url`        VARCHAR(512) NOT NULL                COMMENT 'JDBC URL, 如 jdbc:mysql://host:3306/db?useSSL=false',
    `username`        VARCHAR(128) NOT NULL                COMMENT '账号 (建议外部库使用只读账号)',
    `password_cipher` VARCHAR(512) NOT NULL                COMMENT 'AES-GCM 加密后的密码 Base64; 由 CryptoService 加解密',
    `read_only`       TINYINT(1)   NOT NULL DEFAULT 1     COMMENT '是否强制只读 (首期固定 1, 通过 HikariCP readOnly 实现)',
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 1     COMMENT '是否启用 (0=禁用, 不出现在 list_datasources)',
    `create_by`       VARCHAR(64)  DEFAULT NULL            COMMENT '创建人',
    `create_time`     DATETIME     DEFAULT NULL            COMMENT '创建时间',
    `update_by`       VARCHAR(64)  DEFAULT NULL            COMMENT '更新人',
    `update_time`     DATETIME     DEFAULT NULL            COMMENT '更新时间',
    `del_flag`        INT          DEFAULT 0               COMMENT '删除状态 0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id`            (`user_id`),
    KEY `idx_user_enabled_flag`  (`user_id`, `enabled`, `del_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 可用外部业务数据源';

-- 2. t_sql_audit: SQL 执行审计 (合规留痕, 对应 SqlAuditEvent.Phase 五种状态变迁)
CREATE TABLE `t_sql_audit` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         VARCHAR(64)   NOT NULL                COMMENT '触发查询的用户 ID',
    `conversation_id` VARCHAR(64)   DEFAULT NULL            COMMENT '会话 ID (非聊天场景可为空)',
    `datasource_id`   VARCHAR(64)   NOT NULL                COMMENT '目标数据源 ID',
    `sql_text`        TEXT          NOT NULL                COMMENT '触发审计的 SQL 原文 (EDIT 阶段记录编辑后最终 SQL)',
    `confirm_token`   VARCHAR(64)   DEFAULT NULL            COMMENT '一次性审批 token, 便于跨阶段事件关联',
    `phase`           VARCHAR(16)   NOT NULL                COMMENT '阶段: PENDING / APPROVED / REJECTED / EXECUTED / FAILED',
    `row_count`       INT           DEFAULT NULL            COMMENT '返回行数 (EXECUTED 时填)',
    `elapsed_ms`      BIGINT        DEFAULT NULL            COMMENT '执行耗时 (毫秒, EXECUTED/FAILED 时填)',
    `error_msg`       VARCHAR(1024) DEFAULT NULL            COMMENT '异常摘要 (FAILED 时填, 单条不超过 1KB)',
    `occurred_at`     DATETIME      NOT NULL                COMMENT '事件发生时刻 (业务时间, 与 create_time 区分)',
    `create_by`       VARCHAR(64)   DEFAULT NULL            COMMENT '创建人',
    `create_time`     DATETIME      DEFAULT NULL            COMMENT '创建时间',
    `update_by`       VARCHAR(64)   DEFAULT NULL            COMMENT '更新人',
    `update_time`     DATETIME      DEFAULT NULL            COMMENT '更新时间',
    `del_flag`        INT           DEFAULT 0               COMMENT '删除状态 0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_occurred`      (`user_id`, `occurred_at`),
    KEY `idx_conv_occurred`      (`conversation_id`, `occurred_at`),
    KEY `idx_ds_phase_occurred`  (`datasource_id`, `phase`, `occurred_at`),
    KEY `idx_token`              (`confirm_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL 执行审计 (HITL 状态机留痕)';
