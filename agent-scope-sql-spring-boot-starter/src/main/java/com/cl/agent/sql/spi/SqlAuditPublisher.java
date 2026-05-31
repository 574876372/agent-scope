package com.cl.agent.sql.spi;

/**
 * SQL 审计事件发布 SPI —— **宿主可选实现**。
 *
 * <p>使用场景：
 * <ul>
 *   <li>{@code QueryDatabaseTool} 在颁发 token 前发布一条 PENDING 事件</li>
 *   <li>{@code SqlConfirmExecutor} 在 take token 时发布 APPROVED / REJECTED</li>
 *   <li>{@code SqlConfirmExecutor} 在 JdbcTemplate 执行后发布 EXECUTED / FAILED</li>
 * </ul>
 *
 * <p>若宿主未提供实现，starter 兜底注入 {@code NoOpSqlAuditPublisher}：仅打 INFO 日志，不落库。</p>
 *
 * <p>线程安全要求：实现类必须线程安全；调用 publish 不应阻塞主流程，建议异步落库或队列化处理。</p>
 */
public interface SqlAuditPublisher {

    /**
     * 发布一条 SQL 审计事件。
     *
     * <p>实现方应快速返回；切忌在此方法内执行同步 IO 阻塞 SQL Agent 主链路。
     * 落库失败应仅打 WARN 日志，不抛出异常打断 LLM 流程。</p>
     *
     * @param event 审计事件，含 phase / userId / sql / rowCount / errorMsg 等关键字段；不为 null
     */
    void publish(SqlAuditEvent event);
}
