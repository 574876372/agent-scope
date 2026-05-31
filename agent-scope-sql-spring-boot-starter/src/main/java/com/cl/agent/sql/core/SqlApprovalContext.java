package com.cl.agent.sql.core;

import lombok.Builder;
import lombok.Value;

import java.io.Serializable;
import java.time.Instant;

/**
 * SQL 审批上下文（不可变值对象）。
 *
 * <p>由 {@code QueryDatabaseTool} 在通过守卫后构造，写入 {@code SqlApprovalTokenStore}，
 * 在用户点击 执行/编辑/取消 时由 {@code SqlConfirmExecutor} 通过 token 取回并消费。</p>
 *
 * <p>故意将 token 作为字段而非仅作 Map key，便于把整个上下文当作不透明结构在审计 / 日志中传播。
 * 使用 Lombok {@code @Value} 保证不可变；序列化用于将来支持 Redis 跨进程审批。</p>
 */
@Value
@Builder
public class SqlApprovalContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 一次性令牌，格式 "tok-{uuid}"，由 SqlApprovalTokenStore 生成 */
    String token;

    /** 触发查询的用户 ID（取自 UserContext），confirm 阶段必校验 */
    String userId;

    /** 触发查询的会话 ID（可为空，开放给非聊天场景） */
    String conversationId;

    /** 目标数据源 ID */
    String datasourceId;

    /** 经过 SqlGuard 净化后的 SQL（已强制 LIMIT、剔除注释等） */
    String sql;

    /** EXPLAIN 估算的扫描行数；用于卡片 UI 展示与审计告警 */
    long estimatedRows;

    /** 颁发时刻；与 expiresAt 配合可在日志中直观看到生命周期 */
    Instant createdAt;

    /** 过期时刻；Caffeine 内部会自动驱逐，此字段仅作展示和日志用 */
    Instant expiresAt;
}
