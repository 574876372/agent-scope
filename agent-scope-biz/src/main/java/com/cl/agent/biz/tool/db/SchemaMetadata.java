package com.cl.agent.biz.tool.db;

import lombok.Data;

/**
 * 数据库表元数据实体
 */
@Data
public class SchemaMetadata {
    /** 表名 */
    private String tableName;
    /** 表业务描述 */
    private String description;
    /** 表 DDL 语句 */
    private String ddl;
}
