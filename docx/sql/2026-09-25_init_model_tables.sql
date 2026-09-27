-- ============================================================
-- 模型配置管理（厂商 / 模型）— 数据库初始化脚本
-- 执行前请确保已连接并选择 agent_scope 数据库
--
-- 背景：对话模型与向量模型的接口地址、密钥原先分散在 ModelProviderEnum 枚举与 application.yml 中，
-- 现统一迁移到本脚本创建的两张表，由「模型管理」页面维护；每个知识库创建时绑定一个向量模型。
-- 预置厂商的 api_key_cipher 为空，执行后需在「模型管理」页面补充各厂商 API Key。
--
-- 本脚本可重复执行：建表带 IF NOT EXISTS，预置数据按主键去重（已存在的行保持不变，不会覆盖页面上录入的密钥），
-- 知识库新增列前先检查列是否存在。
-- ============================================================

-- 1. 模型厂商表：接口地址与加密后的密钥，同一厂商下的模型共用
CREATE TABLE IF NOT EXISTS `t_model_provider` (
    `id`             VARCHAR(64)   NOT NULL             COMMENT '厂商唯一标识符 ID',
    `code`           VARCHAR(64)   NOT NULL             COMMENT '厂商编码，创建后不可修改；t_agent_info.model_type 引用该值',
    `name`           VARCHAR(128)  NOT NULL             COMMENT '厂商显示名称',
    `protocol`       VARCHAR(32)   NOT NULL DEFAULT 'OPENAI' COMMENT '接口协议: OPENAI=OpenAI 兼容协议',
    `base_url`       VARCHAR(512)  NOT NULL             COMMENT '接口基础地址',
    `api_key_cipher` VARCHAR(1024) DEFAULT NULL         COMMENT 'AES-GCM 加密后的 API Key Base64；由 CryptoService 加解密，为空表示未配置',
    `enabled`        TINYINT       NOT NULL DEFAULT 1   COMMENT '是否启用 1 启用 0 停用',
    `create_by`      VARCHAR(64)   DEFAULT NULL         COMMENT '创建人',
    `create_time`    DATETIME      DEFAULT NULL         COMMENT '创建时间',
    `update_by`      VARCHAR(64)   DEFAULT NULL         COMMENT '更新人',
    `update_time`    DATETIME      DEFAULT NULL         COMMENT '更新时间',
    `del_flag`       INT           DEFAULT 0            COMMENT '删除状态 0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型厂商连接信息表';

-- 2. 模型表：对话模型（CHAT）与向量模型（EMBEDDING）
CREATE TABLE IF NOT EXISTS `t_model` (
    `id`              VARCHAR(64)  NOT NULL             COMMENT '模型唯一标识符 ID',
    `provider_id`     VARCHAR(64)  NOT NULL             COMMENT '所属厂商 ID，关联 t_model_provider.id',
    `model_type`      VARCHAR(16)  NOT NULL             COMMENT '模型类型: CHAT=对话模型 / EMBEDDING=向量模型',
    `model_name`      VARCHAR(128) NOT NULL             COMMENT '调用接口时使用的模型名称',
    `dimensions`      INT          DEFAULT NULL         COMMENT '向量维度，仅 EMBEDDING；须与模型实际输出一致，同时作为向量库索引维度',
    `send_dimensions` TINYINT      NOT NULL DEFAULT 0   COMMENT '是否将维度作为请求参数发送，仅 EMBEDDING；1 是 0 否',
    `is_default`      TINYINT      NOT NULL DEFAULT 0   COMMENT '是否为同类型默认模型 1 是 0 否',
    `enabled`         TINYINT      NOT NULL DEFAULT 1   COMMENT '是否启用 1 启用 0 停用',
    `create_by`       VARCHAR(64)  DEFAULT NULL         COMMENT '创建人',
    `create_time`     DATETIME     DEFAULT NULL         COMMENT '创建时间',
    `update_by`       VARCHAR(64)  DEFAULT NULL         COMMENT '更新人',
    `update_time`     DATETIME     DEFAULT NULL         COMMENT '更新时间',
    `del_flag`        INT          DEFAULT 0            COMMENT '删除状态 0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_provider_id` (`provider_id`),
    KEY `idx_model_type` (`model_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型定义表';

-- 3. 预置厂商（编码与原 ModelProviderEnum.type 一致，已有智能体无需迁移）
INSERT INTO `t_model_provider` (`id`, `code`, `name`, `protocol`, `base_url`, `api_key_cipher`, `enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`) VALUES
('provider-qwen',     'Qwen',     '通义千问', 'OPENAI', 'https://dashscope.aliyuncs.com/compatible-mode/v1', NULL, 1, 'system', NOW(), 'system', NOW(), 0),
('provider-deepseek', 'DeepSeek', 'DeepSeek', 'OPENAI', 'https://api.deepseek.com/v1',                       NULL, 1, 'system', NOW(), 'system', NOW(), 0),
('provider-openai',   'OpenAI',   'OpenAI',   'OPENAI', 'https://api.openai.com/v1',                         NULL, 1, 'system', NOW(), 'system', NOW(), 0)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- 4. 预置对话模型（原 ModelProviderEnum.models），qwen-plus 为默认
INSERT INTO `t_model` (`id`, `provider_id`, `model_type`, `model_name`, `dimensions`, `send_dimensions`, `is_default`, `enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`) VALUES
('model-qwen-turbo',        'provider-qwen',     'CHAT', 'qwen-turbo',        NULL, 0, 0, 1, 'system', NOW(), 'system', NOW(), 0),
('model-qwen-plus',         'provider-qwen',     'CHAT', 'qwen-plus',         NULL, 0, 1, 1, 'system', NOW(), 'system', NOW(), 0),
('model-qwen-max',          'provider-qwen',     'CHAT', 'qwen-max',          NULL, 0, 0, 1, 'system', NOW(), 'system', NOW(), 0),
('model-qwen-long',         'provider-qwen',     'CHAT', 'qwen-long',         NULL, 0, 0, 1, 'system', NOW(), 'system', NOW(), 0),
('model-qwen3-5-27b',       'provider-qwen',     'CHAT', 'Qwen3.5-27B',       NULL, 0, 0, 1, 'system', NOW(), 'system', NOW(), 0),
('model-deepseek-chat',     'provider-deepseek', 'CHAT', 'deepseek-chat',     NULL, 0, 0, 1, 'system', NOW(), 'system', NOW(), 0),
('model-deepseek-reasoner', 'provider-deepseek', 'CHAT', 'deepseek-reasoner', NULL, 0, 0, 1, 'system', NOW(), 'system', NOW(), 0),
('model-gpt-3-5-turbo',     'provider-openai',   'CHAT', 'gpt-3.5-turbo',     NULL, 0, 0, 1, 'system', NOW(), 'system', NOW(), 0),
('model-gpt-4',             'provider-openai',   'CHAT', 'gpt-4',             NULL, 0, 0, 1, 'system', NOW(), 'system', NOW(), 0),
('model-gpt-4-turbo',       'provider-openai',   'CHAT', 'gpt-4-turbo',       NULL, 0, 0, 1, 'system', NOW(), 'system', NOW(), 0),
('model-gpt-4o',            'provider-openai',   'CHAT', 'gpt-4o',            NULL, 0, 0, 1, 'system', NOW(), 'system', NOW(), 0)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- 5. 预置默认向量模型（与原 application.yml 中 agent.rag.embedding 配置一致）
INSERT INTO `t_model` (`id`, `provider_id`, `model_type`, `model_name`, `dimensions`, `send_dimensions`, `is_default`, `enabled`, `create_by`, `create_time`, `update_by`, `update_time`, `del_flag`) VALUES
('model-qwen-text-embedding-v2', 'provider-qwen', 'EMBEDDING', 'text-embedding-v2', 1536, 0, 1, 1, 'system', NOW(), 'system', NOW(), 0)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- 6. 知识库绑定向量模型；存量知识库的向量均由 text-embedding-v2 生成，直接指向该模型，无需重建向量
--    MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，先查 information_schema，列不存在时才执行 ALTER
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 't_knowledge_base'
                      AND COLUMN_NAME = 'embedding_model_id');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE `t_knowledge_base` ADD COLUMN `embedding_model_id` VARCHAR(64) DEFAULT NULL COMMENT ''绑定的向量模型 ID，关联 t_model.id；创建时确定，之后不可更换'' AFTER `user_id`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `t_knowledge_base` SET `embedding_model_id` = 'model-qwen-text-embedding-v2' WHERE `embedding_model_id` IS NULL;
