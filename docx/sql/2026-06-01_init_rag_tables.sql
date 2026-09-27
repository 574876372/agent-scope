-- ============================================================
-- 3.6 检索增强生成 (RAG - Knowledge Base) — 数据库初始化脚本
-- 执行前请确保已连接并选择 agent_scope 数据库
-- ============================================================

-- 1. 为 t_agent_info 新增 RAG 配置相关字段
ALTER TABLE `t_agent_info`
    ADD COLUMN `rag_mode`        VARCHAR(16) NOT NULL DEFAULT 'DISABLED'
        COMMENT 'RAG检索驱动模式: DISABLED=禁用 / GENERIC=通用前置自动注入 / AGENTIC=智能体自主工具检索',
    ADD COLUMN `recall_limit`    INT         DEFAULT NULL
        COMMENT '单 Agent 专属检索召回最大切片数，NULL 时使用全局默认值',
    ADD COLUMN `score_threshold` DOUBLE      DEFAULT NULL
        COMMENT '单 Agent 专属检索相关度过滤最低阈值，取值 0.0~1.0，NULL 时使用全局默认值';

-- 2. 新增知识库主表 (t_knowledge_base)
CREATE TABLE `t_knowledge_base` (
    `id`          VARCHAR(64)  NOT NULL     COMMENT '知识库唯一标识符 ID',
    `name`        VARCHAR(128) NOT NULL     COMMENT '知识库名称',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '知识库详细业务描述',
    `avatar`      VARCHAR(255) DEFAULT NULL COMMENT '知识库封面头像链接/标识',
    `user_id`     VARCHAR(64)  NOT NULL     COMMENT '创建并拥有此知识库的用户 ID',
    `create_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
    `create_time` DATETIME     DEFAULT NULL COMMENT '创建时间',
    `update_by`   VARCHAR(64)  DEFAULT NULL COMMENT '更新人',
    `update_time` DATETIME     DEFAULT NULL COMMENT '更新时间',
    `del_flag`    INT          DEFAULT 0    COMMENT '删除状态 0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库基础信息表';

-- 3. 新增知识库文档明细表 (t_knowledge_document)
CREATE TABLE `t_knowledge_document` (
    `id`            VARCHAR(64)  NOT NULL     COMMENT '文档唯一标识符 ID',
    `kb_id`         VARCHAR(64)  NOT NULL     COMMENT '所属知识库唯一的 ID',
    `name`          VARCHAR(255) NOT NULL     COMMENT '上传的原始文档文件名',
    `type`          VARCHAR(32)  NOT NULL     COMMENT '文件扩展名类型 (pdf, txt, md, docx, xlsx, xml等)',
    `status`        VARCHAR(32)  NOT NULL     COMMENT '解析状态: uploading/parsing/indexed/failed',
    `size_bytes`    BIGINT       DEFAULT 0    COMMENT '文档物理文件字节大小',
    `char_count`    INT          DEFAULT 0    COMMENT '文本总字数/字符数',
    `chunk_count`   INT          DEFAULT 0    COMMENT '经切片算法分割后的切片总数',
    `file_path`     VARCHAR(512) NOT NULL     COMMENT '在服务器本地存储的物理文件绝对路径',
    `error_message` VARCHAR(512) DEFAULT NULL COMMENT '状态为 failed 时的失败/异常描述信息',
    `create_by`     VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
    `create_time`   DATETIME     DEFAULT NULL COMMENT '创建时间',
    `update_by`     VARCHAR(64)  DEFAULT NULL COMMENT '更新人',
    `update_time`   DATETIME     DEFAULT NULL COMMENT '更新时间',
    `del_flag`      INT          DEFAULT 0    COMMENT '删除状态 0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_kb_id` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档元数据明细表';

-- 4. 新增知识库文本切片表 (t_knowledge_chunk)
CREATE TABLE `t_knowledge_chunk` (
    `id`          VARCHAR(64) NOT NULL     COMMENT '切片唯一标识符 ID',
    `doc_id`      VARCHAR(64) NOT NULL     COMMENT '所属文档 ID',
    `kb_id`       VARCHAR(64) NOT NULL     COMMENT '所属知识库 ID',
    `content`     TEXT        NOT NULL     COMMENT '清洗切割后的纯文本内容',
    `chunk_index` INT         NOT NULL     COMMENT '文本切片在原文档中的物理顺序序号 (0-indexed)',
    `token_count` INT         DEFAULT 0    COMMENT '预估消耗/占用的 LLM Token 数量',
    `create_by`   VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `create_time` DATETIME    DEFAULT NULL COMMENT '创建时间',
    `update_by`   VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `update_time` DATETIME    DEFAULT NULL COMMENT '更新时间',
    `del_flag`    INT         DEFAULT 0    COMMENT '删除状态 0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_doc_id` (`doc_id`),
    KEY `idx_kb_id` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档文本段落切片表';

-- 5. 新增 Agent 与知识库多对多关联表 (t_agent_kb_rel)
CREATE TABLE `t_agent_kb_rel` (
    `id`          VARCHAR(64) NOT NULL     COMMENT '关联主键唯一 ID',
    `agent_id`    VARCHAR(64) NOT NULL     COMMENT '智能体唯一 ID',
    `kb_id`       VARCHAR(64) NOT NULL     COMMENT '关联绑定的知识库唯一 ID',
    `create_by`   VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `create_time` DATETIME    DEFAULT NULL COMMENT '创建时间',
    `update_by`   VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `update_time` DATETIME    DEFAULT NULL COMMENT '更新时间',
    `del_flag`    INT         DEFAULT 0    COMMENT '删除状态 0 正常 1 删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_kb` (`agent_id`, `kb_id`),
    KEY `idx_kb_id` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 与知识库多对多授权绑定表';
