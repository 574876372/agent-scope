package com.cl.agent.sql.core;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 查询代价估算器：在执行 SELECT 前先跑 EXPLAIN，估算扫描行数与潜在风险。
 *
 * <p>这是 HITL 卡片"预估行数 / 警告"展示的数据来源，也是审计事件中 estimatedRows 的来源。
 * EXPLAIN 不会真正读取数据行，安全且代价低；只读连接同样可执行。</p>
 *
 * <p>失败时返回 {@code rows=-1} 与 warning 信息，**绝不阻塞主流程**——估算只是辅助决策，
 * 拿不到也允许进入审批流程。</p>
 */
@Slf4j
public class QueryCostEstimator {

    /** 方言路由器，决定 EXPLAIN 语法 */
    private final DialectRouter dialectRouter;

    /** 全局配置，读告警阈值 */
    private final SqlAgentProperties props;

    /**
     * 构造方法。
     *
     * @param dialectRouter 必填
     * @param props         必填
     */
    public QueryCostEstimator(DialectRouter dialectRouter, SqlAgentProperties props) {
        this.dialectRouter = Objects.requireNonNull(dialectRouter, "dialectRouter");
        this.props = Objects.requireNonNull(props, "props");
    }

    /**
     * 估算给定 SELECT 的扫描行数。
     *
     * <p>使用说明：调用方应已通过 {@link SqlGuardEngine} 校验过 sanitizedSql。
     * 本方法只读，不修改入参，可安全在并发线程中调用。</p>
     *
     * @param dataSource 已解析的 JDBC DataSource
     * @param dbType     数据库类型
     * @param sql        经过守卫的 SQL
     * @return {@link CostEstimate}，含 estimatedRows 与 warning 文本（失败时 estimatedRows=-1）
     */
    public CostEstimate estimate(DataSource dataSource, String dbType, String sql) {
        CostEstimate result = new CostEstimate();
        result.setEstimatedRows(-1);
        if (sql == null || sql.isBlank()) {
            result.setWarning("SQL 为空，跳过估算");
            return result;
        }
        Dialect dialect = dialectRouter.of(dbType);
        String explainSql = dialect.explainSql(sql);
        try {
            JdbcTemplate jt = new JdbcTemplate(dataSource);
            jt.setQueryTimeout(Math.max(1, props.getExecutionTimeoutSeconds()));
            List<Map<String, Object>> rows = jt.queryForList(explainSql);
            long total = 0L;
            for (Map<String, Object> row : rows) {
                Object rowsObj = row.getOrDefault("rows", row.get("ROWS"));
                if (rowsObj instanceof Number n) {
                    total += n.longValue();
                }
            }
            result.setEstimatedRows(total);
            if (total > props.getExplainRowThreshold()) {
                result.setWarning("EXPLAIN 估算扫描行数 " + total + " 超过阈值 "
                        + props.getExplainRowThreshold() + "，请确认是否真的需要执行");
            }
        } catch (Exception e) {
            log.warn("[QueryCostEstimator] EXPLAIN 失败: {}", e.getMessage());
            result.setWarning("EXPLAIN 失败，跳过估算：" + e.getMessage());
        }
        return result;
    }

    /**
     * 估算结果值对象。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostEstimate {
        /** 估算扫描行数；-1 表示失败 */
        private long estimatedRows;
        /** 非致命提示，可为空 */
        private String warning;
    }
}
