package com.cl.agent.sql.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Schema 元数据抽取器：列出表、获取 DDL 文本，供 LLM 决策 SQL 生成。
 *
 * <h2>缓存策略</h2>
 * <ul>
 *   <li>表清单：key={@code "tables:"+dsId}，TTL 5 分钟</li>
 *   <li>建表 DDL：key={@code "ddl:"+dsId+":"+tableName}，TTL 5 分钟</li>
 * </ul>
 * 业务库 schema 变更频率低，5 分钟内的旧数据可接受；过期后自动重拉。
 *
 * <h2>错误处理</h2>
 * <p>任何 SQL 执行异常都被吞掉并返回 {@code "<查询失败：xxx>"} 文本而非抛异常，
 * 让 LLM 看到失败原因后可尝试调用其它工具，不打断 Agent 主链路。</p>
 */
@Slf4j
public class SchemaRetriever {

    /** Caffeine 缓存：键 → 结果（表清单或 DDL 字符串） */
    private final Cache<String, Object> cache;

    /** 方言路由器，根据数据源 dbType 选择 SQL */
    private final DialectRouter dialectRouter;

    /**
     * 构造方法。
     *
     * @param dialectRouter 方言路由器，必填
     * @param props         配置；当前仅用 {@code executionTimeoutSeconds} 作为元数据查询的最大耗时
     */
    public SchemaRetriever(DialectRouter dialectRouter, SqlAgentProperties props) {
        this.dialectRouter = Objects.requireNonNull(dialectRouter, "dialectRouter");
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(2_048)
                .build();
        log.info("[SchemaRetriever] 初始化完成: ttl=5min, maxSize=2048, queryTimeout={}s",
                props.getExecutionTimeoutSeconds());
    }

    /**
     * 列出指定数据源的全部基础表。
     *
     * @param dsId      数据源 ID，仅作缓存 key
     * @param dataSource 已构造好的 JDBC DataSource（由 DatasourceProvider 解析）
     * @param dbType    数据库类型，决定使用哪种方言
     * @return 表元数据列表，每项 {@code "tableName  -- comment"}；查询失败返回空列表
     */
    @SuppressWarnings("unchecked")
    public List<String> listTables(String dsId, DataSource dataSource, String dbType) {
        String key = "tables:" + dsId;
        Object cached = cache.getIfPresent(key);
        if (cached instanceof List) {
            return (List<String>) cached;
        }
        Dialect dialect = dialectRouter.of(dbType);
        List<String> result = new ArrayList<>();
        try {
            JdbcTemplate jt = new JdbcTemplate(dataSource);
            List<Map<String, Object>> rows = jt.queryForList(dialect.listTablesSql());
            for (Map<String, Object> row : rows) {
                String name = String.valueOf(row.getOrDefault("TABLE_NAME", row.getOrDefault("table_name", "")));
                String comment = String.valueOf(row.getOrDefault("TABLE_COMMENT", row.getOrDefault("table_comment", "")));
                if (comment.isBlank() || "null".equals(comment)) {
                    result.add(name);
                } else {
                    result.add(name + "  -- " + comment);
                }
            }
            cache.put(key, result);
        } catch (Exception e) {
            log.warn("[SchemaRetriever] 列表查询失败, dsId={}: {}", dsId, e.getMessage());
        }
        return result;
    }

    /**
     * 获取指定表的建表 DDL 文本（多表批量返回，按表名顺序拼接）。
     *
     * @param dsId       数据源 ID
     * @param dataSource JDBC DataSource
     * @param dbType     数据库类型
     * @param tables     待查询表名列表，必填非空
     * @return 拼接后的 DDL 文本，每张表之间空行分隔；某张表查询失败时插入 {@code -- <表名> 查询失败：原因}
     */
    public String describeTablesAsDdl(String dsId, DataSource dataSource, String dbType, List<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return "";
        }
        Dialect dialect = dialectRouter.of(dbType);
        StringBuilder sb = new StringBuilder();
        JdbcTemplate jt = new JdbcTemplate(dataSource);
        for (String table : tables) {
            String key = "ddl:" + dsId + ":" + table;
            String ddl = (String) cache.getIfPresent(key);
            if (ddl == null) {
                ddl = fetchSingleDdl(jt, dialect, table);
                if (ddl != null && !ddl.startsWith("-- ")) {
                    cache.put(key, ddl);
                }
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(ddl);
        }
        return sb.toString();
    }

    /**
     * 实际执行 {@code SHOW CREATE TABLE}，返回 DDL 字符串或错误提示。
     *
     * @param jt        JdbcTemplate
     * @param dialect   方言
     * @param tableName 表名
     * @return DDL 文本；失败时返回以 {@code "-- "} 起始的错误提示行
     */
    private String fetchSingleDdl(JdbcTemplate jt, Dialect dialect, String tableName) {
        try {
            List<Map<String, Object>> rows = jt.queryForList(dialect.showCreateTableSql(tableName));
            if (rows.isEmpty()) {
                return "-- " + tableName + " 不存在";
            }
            Map<String, Object> row = rows.get(0);
            // MySQL 的 SHOW CREATE TABLE 返回列名为 "Create Table"
            Object ddl = row.get("Create Table");
            if (ddl == null) {
                // 兜底：取第二列
                ddl = row.values().stream().skip(1).findFirst().orElse(null);
            }
            return ddl != null ? ddl.toString() : "-- " + tableName + " 无 DDL 返回";
        } catch (Exception e) {
            log.warn("[SchemaRetriever] DDL 查询失败, table={}: {}", tableName, e.getMessage());
            return "-- " + tableName + " 查询失败：" + e.getMessage();
        }
    }

    /**
     * 主动失效指定数据源的所有缓存（数据源被宿主删除/更新时调用）。
     *
     * <p>使用说明：作为软优化，未调用也只是延迟 5 分钟自动过期，不影响正确性。</p>
     *
     * @param dsId 数据源 ID
     * @return 无返回值；副作用为清理 cache 中对应前缀的条目
     */
    public void invalidate(String dsId) {
        cache.asMap().keySet().removeIf(k -> k.startsWith("tables:" + dsId) || k.startsWith("ddl:" + dsId + ":"));
    }
}
