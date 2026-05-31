package com.cl.agent.biz.sql;

import com.cl.agent.model.SqlAuditLog;
import com.cl.agent.service.ISqlAuditService;
import com.cl.agent.sql.spi.SqlAuditEvent;
import com.cl.agent.sql.spi.SqlAuditPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 宿主侧 {@link SqlAuditPublisher} 实现：把 starter 事件落地到 {@code t_sql_audit}。
 *
 * <p>对 NoOp 兜底的覆盖通过 {@code @ConditionalOnMissingBean} 自动生效。
 * 任何异常都被吞为 WARN 日志，确保审计失败不会拖累 SQL Agent 主链路。</p>
 */
@Slf4j
@Component
public class HostSqlAuditPublisher implements SqlAuditPublisher {

    /** 审计落库服务 */
    @Autowired
    private ISqlAuditService sqlAuditService;

    /**
     * 接收审计事件并转为实体落库。
     *
     * <p>事件字段直接映射；缺省的 {@code occurredAt} 在 service 层补齐。</p>
     *
     * @param event 事件实例（非 null）
     */
    @Override
    public void publish(SqlAuditEvent event) {
        if (event == null) {
            return;
        }
        try {
            SqlAuditLog entity = SqlAuditLog.builder()
                    .userId(event.getUserId())
                    .conversationId(event.getConversationId())
                    .datasourceId(event.getDatasourceId())
                    .sqlText(event.getSql())
                    .confirmToken(event.getConfirmToken())
                    .phase(event.getPhase() == null ? null : event.getPhase().name())
                    .rowCount(event.getRowCount())
                    .elapsedMs(event.getElapsedMs())
                    .errorMsg(truncate(event.getErrorMsg(), 1024))
                    .occurredAt(event.getOccurredAt() == null ? LocalDateTime.now() : event.getOccurredAt())
                    .build();
            sqlAuditService.save(entity);
        } catch (Exception e) {
            log.warn("[HostSqlAuditPublisher] 落库异常（已吞）: phase={}, err={}",
                    event.getPhase(), e.getMessage());
        }
    }

    /**
     * 截断长字符串到指定长度，避免错误信息把字段撑爆。
     *
     * @param raw    原文
     * @param maxLen 最大长度
     * @return 截断后字符串
     */
    private String truncate(String raw, int maxLen) {
        if (raw == null) {
            return null;
        }
        return raw.length() > maxLen ? raw.substring(0, maxLen) : raw;
    }
}
