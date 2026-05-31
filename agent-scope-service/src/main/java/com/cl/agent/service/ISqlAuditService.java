package com.cl.agent.service;

import com.cl.agent.model.SqlAuditLog;

/**
 * SQL 审计日志服务接口。
 *
 * <p>对应 {@code t_sql_audit} 表的写入侧。{@code HostSqlAuditPublisher}（biz/sql）
 * 在收到 starter SPI {@code SqlAuditEvent} 时把事件转为 {@link SqlAuditLog} 调用本接口落库。</p>
 */
public interface ISqlAuditService {

    /**
     * 保存一条审计记录。
     *
     * <p>使用说明：实现侧应吞掉异常仅打日志，**绝不**因审计落库失败而中断 SQL Agent 主链路；
     * 调用方在循环或异步流中调用都安全。</p>
     *
     * @param log 审计实体，必填；id 字段由数据库自增填充
     * @return 无返回值；副作用为新增一行
     */
    void save(SqlAuditLog log);
}
