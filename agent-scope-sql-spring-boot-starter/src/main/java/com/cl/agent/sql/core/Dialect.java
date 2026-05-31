package com.cl.agent.sql.core;

/**
 * 数据库方言接口。
 *
 * <p>抽象出与具体 RDBMS 相关的 SQL 片段，便于 {@code SchemaRetriever} / {@code QueryCostEstimator}
 * 在不同方言间复用相同的执行逻辑。首期实现仅 {@link MySqlDialect}；后续接入 PostgreSQL / Oracle
 * 时新增对应实现，并在 {@link DialectRouter} 中注册。</p>
 */
public interface Dialect {

    /**
     * 方言标识，与 {@code Datasource.dbType} 字段对应。
     *
     * @return 全小写厂商标识，如 {@code "mysql"}
     */
    String name();

    /**
     * 用于列出当前数据库下所有表的 SQL。
     *
     * <p>结果集需包含两列：表名、表注释（无注释返回空字符串）。</p>
     *
     * @return 可被 JdbcTemplate 直接执行的 SQL 字符串
     */
    String listTablesSql();

    /**
     * 用于获取指定表的建表 DDL 的 SQL。
     *
     * @param tableName 表名（调用方应已校验只含合法标识符）
     * @return SQL 字符串
     */
    String showCreateTableSql(String tableName);

    /**
     * 在用户 SQL 外层包装 EXPLAIN 的 SQL。
     *
     * @param userSql 用户原 SQL（已通过 SqlGuardEngine 校验）
     * @return EXPLAIN 包装后的 SQL
     */
    String explainSql(String userSql);
}
