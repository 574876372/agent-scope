package com.cl.agent.sql.tool;

import com.alibaba.fastjson2.JSONObject;
import com.cl.agent.commons.UserContext;
import com.cl.agent.sql.core.GuardResult;
import com.cl.agent.sql.core.QueryCostEstimator;
import com.cl.agent.sql.core.SqlAgentProperties;
import com.cl.agent.sql.core.SqlApprovalTokenStore;
import com.cl.agent.sql.core.SqlGuardEngine;
import com.cl.agent.sql.spi.DatasourceDescriptor;
import com.cl.agent.sql.spi.DatasourceProvider;
import com.cl.agent.sql.spi.SqlAuditEvent;
import com.cl.agent.sql.spi.SqlAuditPublisher;
import com.cl.agent.tool.annotation.AgentToolDef;
import com.cl.agent.tool.annotation.AgentToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQL Agent 工具：提交 SQL 进入"人工确认"队列（**不直接执行**）。
 *
 * <h2>语义</h2>
 * 这是 HITL 的核心入口：
 * <ol>
 *   <li>解析数据源 → 通过守卫 → 跑 EXPLAIN 估算 → 颁发 token → 发 PENDING 审计</li>
 *   <li>返回结构化 PENDING_APPROVAL JSON 给 LLM；LLM 输出文本时会附带 SQL 让用户复核</li>
 *   <li>前端识别 status=PENDING_APPROVAL，渲染 SqlApprovalCard，用户点击后走 confirmSqlExecution 链路</li>
 * </ol>
 *
 * <h2>LLM 行为约束</h2>
 * 工具描述中已强制要求："当用户消息中已直接给出完整 SQL 语句时，必须将其原样作为 sql 参数传入，
 * 禁止改写、补字段、加表别名、调整 LIMIT、调整 WHERE"。这保障"用户直接输入 SQL"的路径 B 健壮性。
 */
@Slf4j
@Component
public class QueryDatabaseTool {

    /** 全局配置 */
    private final SqlAgentProperties props;

    /** 数据源 SPI */
    private final DatasourceProvider datasourceProvider;

    /** 守卫引擎 */
    private final SqlGuardEngine guardEngine;

    /** 代价估算器 */
    private final QueryCostEstimator costEstimator;

    /** 审批令牌存储 */
    private final SqlApprovalTokenStore tokenStore;

    /** 审计发布器 */
    private final SqlAuditPublisher auditPublisher;

    /**
     * 构造方法。
     *
     * @param props              全局配置
     * @param datasourceProvider 数据源 SPI
     * @param guardEngine        守卫
     * @param costEstimator      EXPLAIN 估算器
     * @param tokenStore         token 存储
     * @param auditPublisher     审计发布器
     */
    public QueryDatabaseTool(SqlAgentProperties props,
                             DatasourceProvider datasourceProvider,
                             SqlGuardEngine guardEngine,
                             QueryCostEstimator costEstimator,
                             SqlApprovalTokenStore tokenStore,
                             SqlAuditPublisher auditPublisher) {
        this.props = Objects.requireNonNull(props, "props");
        this.datasourceProvider = Objects.requireNonNull(datasourceProvider, "datasourceProvider");
        this.guardEngine = Objects.requireNonNull(guardEngine, "guardEngine");
        this.costEstimator = Objects.requireNonNull(costEstimator, "costEstimator");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.auditPublisher = Objects.requireNonNull(auditPublisher, "auditPublisher");
    }

    /**
     * 接收 LLM 提交的 SQL，做守卫校验 + EXPLAIN 估算 + 颁发审批 token。
     *
     * <p>使用说明：本工具仅做"预检"，不会真正访问数据返回数据行。返回 JSON 中的 confirmToken
     * 必须由前端在用户审批后回传，由 {@code SqlConfirmExecutor.execute} 消费方可执行。
     * conversationId 通过 UserContext 之外的方式注入比较复杂，starter 不依赖它，留 null 即可。</p>
     *
     * @param datasourceId 数据源 ID（必填）
     * @param sql          SQL 文本（必填）；当用户直接给出完整 SQL，LLM **必须原样**作为本参数传入
     * @return PENDING_APPROVAL 的 JSON 字符串；守卫失败时返回 status=REJECTED + reason
     */
    @AgentToolDef(
            name = "query_database",
            description = "提交 SQL 进入人工确认队列（不会直接执行，需用户在前端 SQL 卡片点击确认）。" +
                    "重要约束：当用户消息中已直接给出完整 SQL 语句时，必须将其原样作为 sql 参数传入，" +
                    "禁止改写、补字段、加表别名、调整 LIMIT、调整 WHERE；" +
                    "仅当用户用自然语言描述查询意图时，才允许你自行生成 SQL。" +
                    "返回字段：status / confirmToken / sql / estimatedRows / warnings。",
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
    public String queryDatabase(
            @AgentToolParam(name = "datasourceId", description = "数据源 ID") String datasourceId,
            @AgentToolParam(name = "sql", description = "待执行的 SELECT 语句") String sql) {

        String userId = UserContext.getUserId();
        JSONObject result = new JSONObject();

        if (userId == null || userId.isBlank()) {
            result.put("status", "REJECTED");
            result.put("reason", "缺少用户上下文，无法发起审批");
            return result.toString();
        }
        log.info("[SQL-FLOW][4/4] query_database triggered. userId={}, datasourceId={}", userId, datasourceId);
        log.info("[SQL-FLOW][4/4] Original SQL submitted by LLM: {}", sql);
        if (datasourceId == null || datasourceId.isBlank()) {
            log.warn("[SQL-FLOW][4/4] Validation failed: datasourceId is blank");
            result.put("status", "REJECTED");
            result.put("reason", "datasourceId 不能为空");
            return result.toString();
        }

        Optional<DataSource> dsOpt = datasourceProvider.resolve(datasourceId, userId);
        if (dsOpt.isEmpty()) {
            log.warn("[SQL-FLOW][4/4] Validation failed: datasourceId={} not found or unauthorized for userId={}", datasourceId, userId);
            result.put("status", "REJECTED");
            result.put("reason", "数据源不存在或未授权: " + datasourceId);
            return result.toString();
        }
        String dbType = resolveDbType(userId, datasourceId);

        // 1. 守卫校验 + LIMIT 改写
        GuardResult guard = guardEngine.validate(sql, props.getDefaultRowLimit());
        if (!guard.isPassed()) {
            log.warn("[SQL-FLOW][4/4] SQL guard validation failed: {}", guard.getErrorMessage());
            result.put("status", "REJECTED");
            result.put("reason", guard.getErrorMessage());
            return result.toString();
        }
        log.info("[SQL-FLOW][4/4] SQL guard validation passed. Sanitized SQL: {}", guard.getSanitizedSql());

        // 2. EXPLAIN 估算（失败也不阻断）
        QueryCostEstimator.CostEstimate cost = costEstimator.estimate(dsOpt.get(), dbType, guard.getSanitizedSql());
        log.info("[SQL-FLOW][4/4] SQL cost estimate: estimatedRows={}, warning={}", cost.getEstimatedRows(), cost.getWarning());

        // 3. 颁发 token —— conversationId 暂未在工具层透出，留 null（宿主在 audit 落库时可二次补全）
        String token = tokenStore.issue(userId, null, datasourceId, guard.getSanitizedSql(), cost.getEstimatedRows());
        log.info("[SQL-FLOW][4/4] Issued approval token: {}", token);

        // 4. 发布 PENDING 审计
        try {
            auditPublisher.publish(SqlAuditEvent.builder()
                    .phase(SqlAuditEvent.Phase.PENDING)
                    .userId(userId)
                    .datasourceId(datasourceId)
                    .sql(guard.getSanitizedSql())
                    .confirmToken(token)
                    .occurredAt(LocalDateTime.now())
                    .build());
            log.info("[SQL-FLOW][4/4] PENDING sql audit published successfully for token={}", token);
        } catch (Exception e) {
            log.warn("[Tool:query_database] PENDING 审计发布失败（已吞）: {}", e.getMessage());
        }

        // 5. 构造给 LLM 的结构化结果
        result.put("status", "PENDING_APPROVAL");
        result.put("confirmToken", token);
        result.put("token", token);
        result.put("datasourceId", datasourceId);
        result.put("sql", guard.getSanitizedSql());
        result.put("estimatedRows", cost.getEstimatedRows());
        if (cost.getWarning() != null && !cost.getWarning().isBlank()) {
            guard.getWarnings().add(cost.getWarning());
        }
        result.put("warnings", guard.getWarnings());
        result.put("message", "SQL 已通过守卫并发起审批，请用户在卡片上点击 执行 / 编辑 / 取消。" +
                "执行前请勿继续调用其它工具或猜测结果。");
        log.info("[SQL-FLOW][4/4] query_database completed. Status: PENDING_APPROVAL, token={}", token);
        log.info("[Tool:query_database] userId={}, ds={}, token={}, estRows={}",
                userId, datasourceId, token, cost.getEstimatedRows());
        return result.toString();
    }

    /**
     * 反查 dbType；与 GetTableSchemaTool 中同名方法等价，独立维护避免跨工具耦合。
     *
     * @param userId       用户 ID
     * @param datasourceId 数据源 ID
     * @return dbType；未知时回退 "mysql"
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
