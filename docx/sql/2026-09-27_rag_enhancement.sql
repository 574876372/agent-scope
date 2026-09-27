-- ============================================================
-- 知识库检索增强（RAG v2）— 数据库变更脚本
-- 对应方案：docx/功能设计/2026-09-26_rag_enhancement_plan.md 第八节
-- 目标库：agent_scope（执行前请先 USE agent_scope）
--
-- 全文索引使用 ngram 分词器（MySQL 5.7.6+ 内置），默认 ngram_token_size=2，适合中文与字段名 / 错误码混合检索。
-- 存量数据：已入库切片没有章节路径，检索照常可用；在知识库页面执行「重新解析」即可获得章节路径与结构化切片。
-- ============================================================

-- 1. 知识库主表 (t_knowledge_base)：类型预设与知识库级切片 / 扩展参数；参数为 NULL 时取类型预设，预设未指定时取 yml 全局默认
ALTER TABLE `t_knowledge_base`
    ADD COLUMN `kb_type`        VARCHAR(32) NOT NULL DEFAULT 'GENERAL' COMMENT '知识库类型: GENERAL=通用文档 / TECH_DOC=技术接口文档 / FAQ=问答 / TABLE=表格数据' AFTER `embedding_model_id`,
    ADD COLUMN `chunk_strategy` VARCHAR(32) DEFAULT NULL               COMMENT '切片策略: SECTION=按章节 / QA=一问一答 / TABLE_ROW=按行分组；NULL 取类型预设' AFTER `kb_type`,
    ADD COLUMN `chunk_size`     INT         DEFAULT NULL               COMMENT '单个切片最大字符数；NULL 取类型预设或 agent.rag.chunk-size' AFTER `chunk_strategy`,
    ADD COLUMN `chunk_overlap`  INT         DEFAULT NULL               COMMENT '超长段落拆分时相邻切片的重叠字符数；NULL 取 agent.rag.chunk-overlap' AFTER `chunk_size`,
    ADD COLUMN `context_window` INT         DEFAULT NULL               COMMENT '检索命中后向前、向后各补充的相邻切片数；0 表示不扩展，NULL 取类型预设' AFTER `chunk_overlap`;

-- 2. 切片表 (t_knowledge_chunk)：章节路径与切片类型
ALTER TABLE `t_knowledge_chunk`
    ADD COLUMN `section_path` VARCHAR(512) DEFAULT NULL           COMMENT '章节路径，如「三、独立代发 › 请求参数」；存量切片为 NULL' AFTER `content`,
    ADD COLUMN `chunk_type`   VARCHAR(16)  NOT NULL DEFAULT 'text' COMMENT '切片类型: text=正文 / table=表格 / qa=问答对' AFTER `section_path`;

-- 3. 切片表 content 建 ngram 全文索引，供关键词召回（字段名、错误码、条款号等精确词）
ALTER TABLE `t_knowledge_chunk` ADD FULLTEXT INDEX `ft_content` (`content`) WITH PARSER ngram;

-- 4. 切片表按文档 + 序号定位（上下文扩展按序号区间取相邻切片）
ALTER TABLE `t_knowledge_chunk` ADD INDEX `idx_doc_chunk_index` (`doc_id`, `chunk_index`);

-- 5. Agent 表 (t_agent_info)：智能体级检索参数；NULL 均取 agent.rag.retrieval.* 全局默认
ALTER TABLE `t_agent_info`
    ADD COLUMN `query_rewrite`     TINYINT     DEFAULT NULL COMMENT '是否结合对话历史改写检索词 1 是 0 否；NULL 取全局默认' AFTER `score_threshold`,
    ADD COLUMN `context_max_chars` INT         DEFAULT NULL COMMENT '注入上下文总长上限（字符）；NULL 取全局默认' AFTER `query_rewrite`,
    ADD COLUMN `rerank_model_id`   VARCHAR(64) DEFAULT NULL COMMENT '重排模型 ID，关联 t_model.id；NULL 取默认重排模型，未配置则不重排' AFTER `context_max_chars`;

-- 6. 模型表 (t_model)：model_type 增加取值 RERANK，仅更新列注释，结构不变
ALTER TABLE `t_model`
    MODIFY COLUMN `model_type` VARCHAR(16) NOT NULL COMMENT '模型类型: CHAT=对话模型 / EMBEDDING=向量模型 / RERANK=重排模型';

-- 7. 检索评估用例表 (t_rag_eval_case)：问题 + 标准答案要点，用于统计召回率与答案完整度
CREATE TABLE `t_rag_eval_case` (
    `id`              VARCHAR(64)   NOT NULL     COMMENT '用例唯一标识符 ID',
    `kb_id`           VARCHAR(64)   NOT NULL     COMMENT '所属知识库 ID',
    `question`        VARCHAR(1024) NOT NULL     COMMENT '测试问题',
    `expected_points` TEXT          NOT NULL     COMMENT '标准答案要点，每行一个；检索上下文与回答中出现的要点占比即召回率与完整度',
    `create_by`       VARCHAR(64)   DEFAULT NULL COMMENT '创建人',
    `create_time`     DATETIME      DEFAULT NULL COMMENT '创建时间',
    `update_by`       VARCHAR(64)   DEFAULT NULL COMMENT '更新人',
    `update_time`     DATETIME      DEFAULT NULL COMMENT '更新时间',
    `del_flag`        INT           DEFAULT 0    COMMENT '删除状态 0 正常 1 删除',
    PRIMARY KEY (`id`),
    KEY `idx_kb_id` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库检索评估用例表';
