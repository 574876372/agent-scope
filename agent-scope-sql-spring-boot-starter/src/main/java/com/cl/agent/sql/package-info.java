/**
 * agent-scope SQL Agent Starter 根包。
 *
 * <h2>模块定位</h2>
 * 提供"多数据源 Text-to-SQL + 人工确认 (HITL)"的可插拔工具包，对宿主项目零侵入。
 *
 * <h2>核心子包</h2>
 * <ul>
 *   <li>{@code spi} —— 宿主必须实现的服务契约：{@code DatasourceProvider}（解析 datasourceId → DataSource）、
 *       {@code SqlAuditPublisher}（审计事件下游）。</li>
 *   <li>{@code core} —— SQL 守卫、Schema 抽取、成本估算、审批令牌、加密等独立基础设施。</li>
 *   <li>{@code executor} —— {@code SqlConfirmExecutor}：聚合 token 消费 + 二次守卫 + JdbcTemplate 执行 + 审计。</li>
 *   <li>{@code tool} —— 暴露给 LLM 的 {@code @AgentToolDef} 工具：list_datasources / get_table_schema / query_database。</li>
 *   <li>{@code autoconfigure} —— Spring Boot 自动装配，由 {@code agent.sql.enabled=true} 启用。</li>
 * </ul>
 *
 * <h2>依赖方向</h2>
 * 严格单向：宿主 → starter → agent-scope-common。本 starter 不依赖任何 model/dao/service/biz/web 模块。
 */
package com.cl.agent.sql;
