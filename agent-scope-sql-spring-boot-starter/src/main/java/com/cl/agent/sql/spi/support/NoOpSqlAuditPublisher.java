package com.cl.agent.sql.spi.support;

import com.cl.agent.sql.spi.SqlAuditEvent;
import com.cl.agent.sql.spi.SqlAuditPublisher;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link SqlAuditPublisher} 的空操作兜底实现。
 *
 * <p>使用场景：宿主未实现 {@link SqlAuditPublisher} 时，starter 仍可工作；
 * 本类把事件按 INFO 级别打印到日志，便于本地调试和单测验证状态机流转。</p>
 *
 * <p>本类由 {@code SqlAgentAutoConfiguration} 在 {@code @ConditionalOnMissingBean(SqlAuditPublisher.class)}
 * 条件下注入；一旦宿主自定义 Bean 注册，本类即被覆盖。</p>
 */
@Slf4j
public class NoOpSqlAuditPublisher implements SqlAuditPublisher {

    /**
     * 按 phase 不同级别打印审计事件，不做任何持久化。
     *
     * <p>EXECUTED/REJECTED 打 INFO；FAILED 打 WARN；其余 DEBUG。</p>
     *
     * @param event 审计事件，非 null
     */
    @Override
    public void publish(SqlAuditEvent event) {
        if (event == null) {
            return;
        }
        switch (event.getPhase()) {
            case FAILED:
                log.warn("[SQL-Audit] phase={}, user={}, ds={}, elapsedMs={}, err={}, sql={}",
                        event.getPhase(), event.getUserId(), event.getDatasourceId(),
                        event.getElapsedMs(), event.getErrorMsg(), event.getSql());
                break;
            case EXECUTED:
            case REJECTED:
                log.info("[SQL-Audit] phase={}, user={}, ds={}, rows={}, elapsedMs={}, sql={}",
                        event.getPhase(), event.getUserId(), event.getDatasourceId(),
                        event.getRowCount(), event.getElapsedMs(), event.getSql());
                break;
            default:
                log.debug("[SQL-Audit] phase={}, user={}, ds={}, token={}",
                        event.getPhase(), event.getUserId(), event.getDatasourceId(),
                        event.getConfirmToken());
        }
    }
}
