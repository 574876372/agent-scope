package com.cl.agent.sql.spi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * SQL 审计事件值对象。
 *
 * <p>覆盖一条 SQL 从被 LLM 提交到执行结束的全部关键状态变迁，宿主实现 {@link SqlAuditPublisher} 时
 * 将事件持久化到 t_sql_audit，便于后续接入 ELK / Grafana 做合规审计。</p>
 *
 * <p>典型生命周期：
 * <pre>
 *   PENDING (LLM 提交，QueryDatabaseTool 内部)
 *     ├─→ APPROVED (用户点执行，SqlConfirmExecutor 取 token 成功)
 *     │     ├─→ EXECUTED (SELECT 执行成功，rowCount/elapsedMs 填入)
 *     │     └─→ FAILED   (执行异常，errorMsg 填入)
 *     └─→ REJECTED (用户取消)
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlAuditEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态机阶段，对应 {@link Phase} 枚举值 */
    private Phase phase;

    /** 用户 ID（取自 UserContext，多租户隔离） */
    private String userId;

    /** 会话 ID，可为空（如纯工具内部调试场景） */
    private String conversationId;

    /** 目标数据源 ID */
    private String datasourceId;

    /** 触发审计时的 SQL 原文（EDIT 阶段为编辑后的最终 SQL） */
    private String sql;

    /** 一次性令牌；PENDING 阶段由 SqlApprovalTokenStore 颁发，便于后续状态关联 */
    private String confirmToken;

    /** 执行返回的行数，仅 EXECUTED 时填 */
    private Integer rowCount;

    /** 执行耗时（毫秒），EXECUTED/FAILED 时填 */
    private Long elapsedMs;

    /** 异常摘要，FAILED 时填，单条不超过 1KB 防日志爆炸 */
    private String errorMsg;

    /** 事件发生时刻 */
    private LocalDateTime occurredAt;

    /**
     * 审计状态机阶段。
     */
    public enum Phase {
        /** SQL 已通过守卫并发出 token，等待用户审批 */
        PENDING,
        /** 用户已批准（仅作过渡态，紧随其后是 EXECUTED 或 FAILED） */
        APPROVED,
        /** 用户主动取消 */
        REJECTED,
        /** SELECT 执行完成 */
        EXECUTED,
        /** SELECT 执行抛异常 */
        FAILED
    }
}
