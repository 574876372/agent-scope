package com.cl.agent.sql.core;

/**
 * MySQL 方言实现。
 *
 * <p>使用 {@code information_schema} 列出表与列、{@code SHOW CREATE TABLE} 取 DDL；
 * EXPLAIN 直接前缀拼接。所有 SQL 片段假设连接已经选定 database（{@code USE xxx}），
 * 由 HikariCP 在初始化时根据 jdbcUrl 自动完成。</p>
 */
public class MySqlDialect implements Dialect {

    /**
     * {@inheritDoc}
     *
     * @return 固定 {@code "mysql"}
     */
    @Override
    public String name() {
        return "mysql";
    }

    /**
     * {@inheritDoc}
     *
     * <p>仅返回当前 database 下的基础表（排除视图），结果两列：TABLE_NAME / TABLE_COMMENT。</p>
     *
     * @return 列表查询 SQL
     */
    @Override
    public String listTablesSql() {
        return "SELECT TABLE_NAME, IFNULL(TABLE_COMMENT, '') AS TABLE_COMMENT "
                + "FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' "
                + "ORDER BY TABLE_NAME";
    }

    /**
     * {@inheritDoc}
     *
     * <p>反引号包裹表名前先剔除调用方残留的反引号，避免双层包裹。</p>
     *
     * @param tableName 表名
     * @return {@code SHOW CREATE TABLE `xxx`}
     */
    @Override
    public String showCreateTableSql(String tableName) {
        String cleaned = tableName == null ? "" : tableName.replace("`", "");
        return "SHOW CREATE TABLE `" + cleaned + "`";
    }

    /**
     * {@inheritDoc}
     *
     * @param userSql 已通过守卫的 SELECT
     * @return {@code EXPLAIN <user-sql>}
     */
    @Override
    public String explainSql(String userSql) {
        return "EXPLAIN " + userSql;
    }
}
