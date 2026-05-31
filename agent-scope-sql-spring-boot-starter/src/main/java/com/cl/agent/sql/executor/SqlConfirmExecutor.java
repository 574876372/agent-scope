package com.cl.agent.sql.executor;

import com.cl.agent.enums.SqlAction;
import com.cl.agent.exception.BizException;
import com.cl.agent.sql.core.GuardResult;
import com.cl.agent.sql.core.SqlAgentProperties;
import com.cl.agent.sql.core.SqlApprovalContext;
import com.cl.agent.sql.core.SqlApprovalTokenStore;
import com.cl.agent.sql.core.SqlGuardEngine;
import com.cl.agent.sql.spi.DatasourceProvider;
import com.cl.agent.sql.spi.SqlAuditEvent;
import com.cl.agent.sql.spi.SqlAuditPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSetMetaData;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQL 审批执行器：聚合 take token → 二次守卫（EDIT）→ JdbcTemplate 执行 → 审计发布 完整链路。
 *
 * <h2>调用方</h2>
 * 宿主 {@code SqlAgentBizImpl.confirmSqlExecution} 在 {@code ChatBizImpl} 短路分支中调用本执行器。
 * starter 内部不暴露给 LLM，工具 {@code query_database} 仅做 PENDING 状态颁发 token，不直连本执行器。
 *
 * <h2>三类分支</h2>
 * <ul>
 *   <li>{@link SqlAction#APPROVE} —— 直接使用 token 中保存的 SQL 执行</li>
 *   <li>{@link SqlAction#EDIT}    —— 用 editedSql 重过 {@link SqlGuardEngine}，通过后执行</li>
 *   <li>{@link SqlAction#REJECT}  —— 仅发审计事件 + 返回 REJECTED 状态，不访问数据库</li>
 * </ul>
 *
 * <h2>安全要点</h2>
 * <ol>
 *   <li>token 通过 {@link SqlApprovalTokenStore#take} 一次性消费</li>
 *   <li>userId 严格校验：token 所属 userId 必须等于调用方传入的 currentUserId</li>
 *   <li>JdbcTemplate.setQueryTimeout 限制单次执行时长</li>
 *   <li>所有状态变迁通过 {@link SqlAuditPublisher} 留痕</li>
 * </ol>
 */
@Slf4j
public class SqlConfirmExecutor {

    /** 全局配置 */
    private final SqlAgentProperties props;

    /** 数据源解析 SPI */
    private final DatasourceProvider datasourceProvider;

    /** 审批令牌存储 */
    private final SqlApprovalTokenStore tokenStore;

    /** SQL 守卫，EDIT 分支必用 */
    private final SqlGuardEngine guardEngine;

    /** 审计事件发布 SPI */
    private final SqlAuditPublisher auditPublisher;

    /**
     * 构造方法。
     *
     * @param props              全局配置
     * @param datasourceProvider 数据源 SPI（宿主实现，starter 提供 NoOp 兜底）
     * @param tokenStore         审批令牌存储
     * @param guardEngine        SQL 守卫
     * @param auditPublisher     审计 SPI
     */
    public SqlConfirmExecutor(SqlAgentProperties props,
                              DatasourceProvider datasourceProvider,
                              SqlApprovalTokenStore tokenStore,
                              SqlGuardEngine guardEngine,
                              SqlAuditPublisher auditPublisher) {
        this.props = Objects.requireNonNull(props, "props");
        this.datasourceProvider = Objects.requireNonNull(datasourceProvider, "datasourceProvider");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.guardEngine = Objects.requireNonNull(guardEngine, "guardEngine");
        this.auditPublisher = Objects.requireNonNull(auditPublisher, "auditPublisher");
    }

    /**
     * 处理一次审批动作。
     *
     * <p>使用说明：宿主在 SSE 入口短路分支调用本方法，根据返回的 {@link SqlExecutionResult.status}
     * 决定向前端推送什么帧（EXECUTED → tool_result + 二次总结；REJECTED → message；ERROR → error）。
     * 本方法是阻塞调用（JDBC 同步），调用方应在合适的 {@code Schedulers} 上执行。</p>
     *
     * @param token         一次性审批 token，必填
     * @param action        用户动作，必填
     * @param editedSql     仅当 action=EDIT 时使用；其它分支可为 null
     * @param currentUserId 当前请求用户 ID，必填；与 token 所属 userId 不一致时拒绝
     * @return 执行结果；status 字段标识具体走向
     * @throws BizException token 为空、userId 为空、token 不存在/过期、跨用户重放等致命错误
     */
    public SqlExecutionResult execute(String token, SqlAction action, String editedSql, String currentUserId) {
        if (token == null || token.isBlank()) {
            throw new BizException(400, "confirmToken 不能为空");
        }
        if (action == null) {
            throw new BizException(400, "sqlAction 不能为空");
        }
        if (currentUserId == null || currentUserId.isBlank()) {
            throw new BizException(401, "缺少用户上下文");
        }
        Optional<SqlApprovalContext> ctxOpt = tokenStore.take(token);
        if (ctxOpt.isEmpty()) {
            // token 已过期或重复消费
            return SqlExecutionResult.builder()
                    .status("TOKEN_EXPIRED")
                    .message("SQL 审批 token 不存在或已过期，请重新发起查询")
                    .build();
        }
        SqlApprovalContext ctx = ctxOpt.get();
        if (!currentUserId.equals(ctx.getUserId())) {
            log.warn("[SqlConfirmExecutor] token 跨用户重放尝试: tokenUserId={}, current={}", ctx.getUserId(), currentUserId);
            publishAudit(SqlAuditEvent.Phase.REJECTED, ctx, ctx.getSql(), null, null, "跨用户重放被拒绝");
            throw new BizException(403, "无权使用该审批 token");
        }

        switch (action) {
            case REJECT:
                publishAudit(SqlAuditEvent.Phase.REJECTED, ctx, ctx.getSql(), null, null, null);
                return SqlExecutionResult.builder()
                        .status("REJECTED")
                        .sql(ctx.getSql())
                        .datasourceId(ctx.getDatasourceId())
                        .message("用户已取消 SQL 执行")
                        .build();
            case EDIT:
                return runEdit(ctx, editedSql);
            case APPROVE:
            default:
                return runApprove(ctx);
        }
    }

    /**
     * APPROVE 分支：直接执行 token 中的 SQL。
     *
     * @param ctx 审批上下文
     * @return 执行结果
     */
    private SqlExecutionResult runApprove(SqlApprovalContext ctx) {
        publishAudit(SqlAuditEvent.Phase.APPROVED, ctx, ctx.getSql(), null, null, null);
        return doExecute(ctx, ctx.getSql());
    }

    /**
     * EDIT 分支：用 editedSql 重过守卫后执行。
     *
     * <p>校验失败直接返回 ERROR 结果而不抛异常，便于宿主把错误信息推回给用户。</p>
     *
     * @param ctx       审批上下文
     * @param editedSql 用户编辑后的 SQL
     * @return 执行结果
     */
    private SqlExecutionResult runEdit(SqlApprovalContext ctx, String editedSql) {
        if (editedSql == null || editedSql.isBlank()) {
            return SqlExecutionResult.builder()
                    .status("ERROR")
                    .datasourceId(ctx.getDatasourceId())
                    .error("EDIT 动作需要提供 editedSql 字段")
                    .build();
        }
        GuardResult guard = guardEngine.validate(editedSql, props.getDefaultRowLimit());
        if (!guard.isPassed()) {
            publishAudit(SqlAuditEvent.Phase.FAILED, ctx, editedSql, null, null, guard.getErrorMessage());
            return SqlExecutionResult.builder()
                    .status("ERROR")
                    .sql(editedSql)
                    .datasourceId(ctx.getDatasourceId())
                    .error("编辑后的 SQL 校验失败：" + guard.getErrorMessage())
                    .build();
        }
        publishAudit(SqlAuditEvent.Phase.APPROVED, ctx, guard.getSanitizedSql(), null, null, null);
        return doExecute(ctx, guard.getSanitizedSql());
    }

    /**
     * 真正执行 SELECT 并填充行/列。
     *
     * @param ctx       审批上下文（含 datasourceId / userId 等元数据）
     * @param finalSql  最终待执行 SQL（已通过守卫）
     * @return 执行结果（EXECUTED 或 ERROR）
     */
    private SqlExecutionResult doExecute(SqlApprovalContext ctx, String finalSql) {
        Optional<DataSource> dsOpt = datasourceProvider.resolve(ctx.getDatasourceId(), ctx.getUserId());
        if (dsOpt.isEmpty()) {
            String err = "数据源不存在或未授权: " + ctx.getDatasourceId();
            publishAudit(SqlAuditEvent.Phase.FAILED, ctx, finalSql, null, null, err);
            return SqlExecutionResult.builder()
                    .status("ERROR")
                    .sql(finalSql)
                    .datasourceId(ctx.getDatasourceId())
                    .error(err)
                    .build();
        }
        JdbcTemplate jt = new JdbcTemplate(dsOpt.get());
        jt.setQueryTimeout(Math.max(1, props.getExecutionTimeoutSeconds()));

        long start = System.currentTimeMillis();
        try {
            SqlExecutionResult result = jt.query(finalSql, rs -> {
                SqlExecutionResult inner = SqlExecutionResult.builder()
                        .status("EXECUTED")
                        .sql(finalSql)
                        .datasourceId(ctx.getDatasourceId())
                        .columns(new ArrayList<>())
                        .rows(new ArrayList<>())
                        .build();
                ResultSetMetaData md = rs.getMetaData();
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
            }
            publishAudit(SqlAuditEvent.Phase.EXECUTED, ctx, finalSql,
                    result != null ? result.getRowCount() : null, elapsed, null);
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[SqlConfirmExecutor] 执行失败, sql={}: {}", finalSql, e.getMessage());
            publishAudit(SqlAuditEvent.Phase.FAILED, ctx, finalSql, null, elapsed, e.getMessage());
            return SqlExecutionResult.builder()
                    .status("ERROR")
                    .sql(finalSql)
                    .datasourceId(ctx.getDatasourceId())
                    .elapsedMs(elapsed)
                    .error("SQL 执行失败：" + e.getMessage())
                    .build();
        }
    }

    /**
     * 包装审计事件发布；任何异常都被吞掉以保护主链路。
     *
     * @param phase     事件阶段
     * @param ctx       审批上下文
     * @param sql       SQL 文本
     * @param rowCount  返回行数（仅 EXECUTED 有意义）
     * @param elapsedMs 耗时（EXECUTED/FAILED 有意义）
     * @param errorMsg  错误信息（FAILED 有意义）
     */
    private void publishAudit(SqlAuditEvent.Phase phase, SqlApprovalContext ctx, String sql,
                              Integer rowCount, Long elapsedMs, String errorMsg) {
        try {
            auditPublisher.publish(SqlAuditEvent.builder()
                    .phase(phase)
                    .userId(ctx.getUserId())
                    .conversationId(ctx.getConversationId())
                    .datasourceId(ctx.getDatasourceId())
                    .sql(sql)
                    .confirmToken(ctx.getToken())
                    .rowCount(rowCount)
                    .elapsedMs(elapsedMs)
                    .errorMsg(errorMsg)
                    .occurredAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("[SqlConfirmExecutor] 发布审计事件失败（已吞）: {}", e.getMessage());
        }
    }
}
