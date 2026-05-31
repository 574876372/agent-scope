package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * SQL 审计日志实体，对应 {@code t_sql_audit} 表。
 *
 * <p>承载 starter SPI {@code SqlAuditEvent} 的持久化形态。
 * 由 {@code HostSqlAuditPublisher} 在收到事件时构造，{@code ISqlAuditService.save} 落库。</p>
 *
 * <p>{@link #phase} 字段值与 {@code SqlAuditEvent.Phase} 枚举名一一对应，便于跨进程统一语义。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_sql_audit")
public class SqlAuditLog extends BaseEntity {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 触发查询的用户 ID */
    @TableField("user_id")
    private String userId;

    /** 会话 ID，可为空 */
    @TableField("conversation_id")
    private String conversationId;

    /** 目标数据源 ID */
    @TableField("datasource_id")
    private String datasourceId;

    /** SQL 原文 */
    @TableField("sql_text")
    private String sqlText;

    /** 一次性 token */
    @TableField("confirm_token")
    private String confirmToken;

    /** 阶段：PENDING / APPROVED / REJECTED / EXECUTED / FAILED */
    @TableField("phase")
    private String phase;

    /** 返回行数（EXECUTED 阶段） */
    @TableField("row_count")
    private Integer rowCount;

    /** 执行耗时（毫秒，EXECUTED/FAILED 阶段） */
    @TableField("elapsed_ms")
    private Long elapsedMs;

    /** 错误摘要（FAILED 阶段） */
    @TableField("error_msg")
    private String errorMsg;

    /** 事件发生业务时刻（与 BaseEntity.createTime 区分） */
    @TableField("occurred_at")
    private LocalDateTime occurredAt;
}
