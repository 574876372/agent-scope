package com.cl.agent.sql.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.cl.agent.commons.UserContext;
import com.cl.agent.sql.core.GuardResult;
import com.cl.agent.sql.core.QueryCostEstimator;
import com.cl.agent.sql.core.SqlAgentProperties;
import com.cl.agent.sql.core.SqlGuardEngine;
import com.cl.agent.sql.spi.DatasourceDescriptor;
import com.cl.agent.sql.spi.DatasourceProvider;
import com.cl.agent.sql.spi.SqlAuditEvent;
import com.cl.agent.sql.spi.SqlAuditPublisher;
import com.cl.agent.sql.executor.SqlExecutionResult;
import com.cl.agent.tool.annotation.AgentToolDef;
import com.cl.agent.tool.annotation.AgentToolParam;
import com.cl.agent.tool.annotation.RequiresApproval;
import com.cl.agent.tool.annotation.PreCheckHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SQL Agent 执行工具类。
 * <p>使用说明：实现了 {@link PreCheckHandler}，在 Agent 调用本工具前，会触发预检安全守卫及 EXPLAIN 代价估算，
 * 并将其返回结果发给前端确认。当用户在前端审批卡片点击同意后，本方法的真实方法体才会被执行，
 * 真正访问数据源并返回序列化为 JSON 字符串的 {@link SqlExecutionResult}，回流给大模型做二次总结。</p>
 */
@Slf4j
@Component
public class QueryDatabaseTool implements PreCheckHandler {

    /** SQL 全局配置属性 */
    private final SqlAgentProperties props;

    /** 数据源提供器 SPI，用于获取连接 */
    private final DatasourceProvider datasourceProvider;

    /** SQL 语法安全校验守卫 */
    private final SqlGuardEngine guardEngine;

    /** 代价估算器，执行 EXPLAIN */
    private final QueryCostEstimator costEstimator;

    /** 审计事件发布器，记录操作日志 */
    private final SqlAuditPublisher auditPublisher;

    /**
     * 构造方法。
     * <p>使用说明：由 Spring 自动注入所需的核心 Bean，无需手动配置。</p>
     *
     * @param props              全局配置，非空
     * @param datasourceProvider 数据源 SPI，非空
     * @param guardEngine        守卫，非空
     * @param costEstimator      EXPLAIN 估算器，非空
     * @param auditPublisher     审计发布器，非空
     */
    public QueryDatabaseTool(SqlAgentProperties props,
                             DatasourceProvider datasourceProvider,
                             SqlGuardEngine guardEngine,
                             QueryCostEstimator costEstimator,
                             SqlAuditPublisher auditPublisher) {
        this.props = Objects.requireNonNull(props, "props");
        this.datasourceProvider = Objects.requireNonNull(datasourceProvider, "datasourceProvider");
        this.guardEngine = Objects.requireNonNull(guardEngine, "guardEngine");
        this.costEstimator = Objects.requireNonNull(costEstimator, "costEstimator");
        this.auditPublisher = Objects.requireNonNull(auditPublisher, "auditPublisher");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Object> preCheck(Map<String, Object> parameters) {
        String sql = (String) parameters.get("sql");
        String datasourceId = (String) parameters.get("datasourceId");
        String userId = UserContext.getUserId();

        Map<String, Object> preCheckMeta = new HashMap<>();
        if (userId == null || userId.isBlank()) {
            preCheckMeta.put("warnings", List.of("缺少当前用户上下文，拒绝预检。"));
            preCheckMeta.put("estimatedRows", -1L);
            return preCheckMeta;
        }

        // 1. 守卫静态校验及限制改写
        GuardResult guard = guardEngine.validate(sql, props.getDefaultRowLimit());
        if (!guard.isPassed()) {
            preCheckMeta.put("warnings", List.of(guard.getErrorMessage()));
            preCheckMeta.put("estimatedRows", -1L);
            return preCheckMeta;
        }

        // 2. 尝试执行 EXPLAIN 估算扫描行数
        long estimatedRows = -1L;
        List<String> warnings = new ArrayList<>(guard.getWarnings());
        Optional<DataSource> dsOpt = datasourceProvider.resolve(datasourceId, userId);
        if (dsOpt.isPresent()) {
            String dbType = resolveDbType(userId, datasourceId);
            QueryCostEstimator.CostEstimate cost = costEstimator.estimate(dsOpt.get(), dbType, guard.getSanitizedSql());
            estimatedRows = cost.getEstimatedRows();
            if (cost.getWarning() != null && !cost.getWarning().isBlank()) {
                warnings.add(cost.getWarning());
            }
        }

        preCheckMeta.put("estimatedRows", estimatedRows);
        preCheckMeta.put("warnings", warnings);
        preCheckMeta.put("sql", guard.getSanitizedSql());

        // 3. 发布 PENDING 审计事件
        try {
            auditPublisher.publish(SqlAuditEvent.builder()
                    .phase(SqlAuditEvent.Phase.PENDING)
                    .userId(userId)
                    .datasourceId(datasourceId)
                    .sql(guard.getSanitizedSql())
                    .occurredAt(LocalDateTime.now())
                    .build());
            log.info("[SQL-Tool] PENDING 审计发布成功: sql={}", guard.getSanitizedSql());
        } catch (Exception e) {
            log.warn("[SQL-Tool] 发布 PENDING 审计失败（已吞）: {}", e.getMessage());
        }

        return preCheckMeta;
    }

    /**
     * 接收并真实执行通过审批的 SQL 查询。
     * <p>使用说明：本工具标有 {@link RequiresApproval}。首轮调用会被 HITL 自动拦截并生成 Token，
     * 用户确认后由业务层续写触发真实执行。会运行二次守卫、设置查询超时、并向审计服务写入日志。</p>
     *
     * @param datasourceId 数据源 ID，由前置 list_datasources 返回，必填
     * @param sql          SELECT 查询 SQL 语句，必填
     * @return 序列化后的 {@link SqlExecutionResult} JSON 字符串，包含执行状态、行数据、列名及可能发生的异常报错
     */
    @AgentToolDef(
            name = "query_database",
            description = "提交 SQL 并真实执行。这是一个高危的敏感操作，调用前系统会自动拦截触发人机协同审批卡片，人类点击同意后本方法才会被运行。" +
                    "重要约束：当用户消息中已直接给出完整 SQL 语句时，必须将其原样作为 sql 参数传入，" +
                    "禁止改写、补字段、加表别名、调整 LIMIT、调整 WHERE；" +
                    "仅当用户用自然语言描述查询意图时，才允许你自行生成 SQL。" +
                    "返回字段：status / columns / rows / rowCount / elapsedMs / error。",
            parametersSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "datasourceId": {
                          "type": "string",
                          "description": "数据源 ID，由 list_datasources 返回"
                        },
                        "sql": {
                          "type": "string",
                          "description": "SELECT 查询语句；不允许 INSERT/UPDATE/DELETE/DDL"
                        }
                      },
                      "required": ["datasourceId", "sql"]
                    }
                    """
    )
    @RequiresApproval
    public String queryDatabase(
            @AgentToolParam(name = "datasourceId", description = "数据源 ID") String datasourceId,
            @AgentToolParam(name = "sql", description = "待执行的 SELECT 语句") String sql) {

        String userId = UserContext.getUserId();
        if (userId == null || userId.isBlank()) {
            return buildErrorResult("缺少用户上下文，拒绝执行", datasourceId, sql);
        }

        log.info("[SQL-Tool] 执行审批通过的 SQL 真实请求. userId={}, datasourceId={}", userId, datasourceId);

        // 1. 二次守卫安全重校验及最终改写
        GuardResult guard = guardEngine.validate(sql, props.getDefaultRowLimit());
        if (!guard.isPassed()) {
            String errMsg = "SQL 校验失败: " + guard.getErrorMessage();
            publishAudit(SqlAuditEvent.Phase.FAILED, userId, datasourceId, sql, null, null, errMsg);
            return buildErrorResult(errMsg, datasourceId, sql);
        }

        String finalSql = guard.getSanitizedSql();

        // 2. 发布 APPROVED 确认通过审计事件
        publishAudit(SqlAuditEvent.Phase.APPROVED, userId, datasourceId, finalSql, null, null, null);

        // 3. 获取数据源连接并反射运行
        Optional<DataSource> dsOpt = datasourceProvider.resolve(datasourceId, userId);
        if (dsOpt.isEmpty()) {
            String errMsg = "数据源不存在或未授权: " + datasourceId;
            publishAudit(SqlAuditEvent.Phase.FAILED, userId, datasourceId, finalSql, null, null, errMsg);
            return buildErrorResult(errMsg, datasourceId, finalSql);
        }

        JdbcTemplate jt = new JdbcTemplate(dsOpt.get());
        jt.setQueryTimeout(Math.max(1, props.getExecutionTimeoutSeconds()));

        long start = System.currentTimeMillis();
        try {
            SqlExecutionResult result = jt.query(finalSql, rs -> {
                SqlExecutionResult inner = SqlExecutionResult.builder()
                        .status("EXECUTED")
                        .sql(finalSql)
                        .datasourceId(datasourceId)
                        .columns(new ArrayList<>())
                        .rows(new ArrayList<>())
                        .build();
                java.sql.ResultSetMetaData md = rs.getMetaData();
                int columnCount = md.getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    inner.getColumns().add(md.getColumnLabel(i));
                }
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    inner.getRows().add(row);
                }
                inner.setRowCount(inner.getRows().size());
                inner.setTruncated(inner.getRowCount() >= props.getDefaultRowLimit());
                return inner;
            });

            long elapsed = System.currentTimeMillis() - start;
            if (result != null) {
                result.setElapsedMs(elapsed);
                publishAudit(SqlAuditEvent.Phase.EXECUTED, userId, datasourceId, finalSql, result.getRowCount(), elapsed, null);
                return JSON.toJSONString(result);
            }
            return buildErrorResult("数据源未返回任何执行记录", datasourceId, finalSql);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[SQL-Tool] SQL 运行异常, sql={}", finalSql, e);
            String errMsg = "SQL 运行异常: " + e.getMessage();
            publishAudit(SqlAuditEvent.Phase.FAILED, userId, datasourceId, finalSql, null, elapsed, errMsg);
            return buildErrorResult(errMsg, datasourceId, finalSql);
        }
    }

    /**
     * 构建包含错信息描述的 {@link SqlExecutionResult} JSON 字符串。
     *
     * @param error        错误消息
     * @param datasourceId 数据源 ID
     * @param sql          执行 SQL
     * @return 错误结果 JSON
     */
    private String buildErrorResult(String error, String datasourceId, String sql) {
        SqlExecutionResult result = SqlExecutionResult.builder()
                .status("ERROR")
                .error(error)
                .datasourceId(datasourceId)
                .sql(sql)
                .build();
        return JSON.toJSONString(result);
    }

    /**
     * 自动上报 SQL 审计事件。
     *
     * @param phase        事件阶段
     * @param userId       用户 ID
     * @param datasourceId 数据源 ID
     * @param sql          执行的 SQL
     * @param rowCount     返回的行数
     * @param elapsedMs    执行耗时毫秒
     * @param errorMsg     错误信息内容
     */
    private void publishAudit(SqlAuditEvent.Phase phase, String userId, String datasourceId, String sql,
                              Integer rowCount, Long elapsedMs, String errorMsg) {
        try {
            auditPublisher.publish(SqlAuditEvent.builder()
                    .phase(phase)
                    .userId(userId)
                    .datasourceId(datasourceId)
                    .sql(sql)
                    .rowCount(rowCount)
                    .elapsedMs(elapsedMs)
                    .errorMsg(errorMsg)
                    .occurredAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("[SQL-Tool] 发布事件 {} 审计失败", phase, e);
        }
    }

    /**
     * 获取指定数据源的方言类型（默认 mysql）。
     *
     * @param userId       用户 ID
     * @param datasourceId 数据源 ID
     * @return 方言名称，默认为 "mysql"
     */
    private String resolveDbType(String userId, String datasourceId) {
        try {
            List<DatasourceDescriptor> list = datasourceProvider.listAvailable(userId);
            for (DatasourceDescriptor d : list) {
                if (datasourceId.equals(d.getId())) {
                    return d.getDbType() != null ? d.getDbType() : "mysql";
                }
            }
        } catch (Exception ignore) {
            // 静默回退
        }
        return "mysql";
    }
}
