package com.cl.agent.sql.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SQL Agent starter 全局配置。
 *
 * <p>绑定 {@code application.yml} 中的 {@code agent.sql.*} 配置项。
 * 由 {@code SqlAgentAutoConfiguration} 通过 {@code @EnableConfigurationProperties} 启用。</p>
 *
 * <p>使用场景：
 * <ul>
 *   <li>{@code SqlGuardEngine} 读 {@link #defaultRowLimit} 强制改写 LIMIT</li>
 *   <li>{@code QueryCostEstimator} 读 {@link #explainRowThreshold} 触发预警标记</li>
 *   <li>{@code SqlApprovalTokenStore} 读 {@link #tokenTtlSeconds} 设置 Caffeine TTL</li>
 *   <li>{@code SqlConfirmExecutor} 读 {@link #executionTimeoutSeconds} 限制 JdbcTemplate 查询超时</li>
 *   <li>{@code CryptoService} 读 {@link #cryptoKey} 派生 AES 密钥</li>
 * </ul>
 */
@Data
@ConfigurationProperties("agent.sql")
public class SqlAgentProperties {

    /** 是否启用 SQL Agent 工具包；默认 false，由宿主在 application.yml 中显式开启 */
    private boolean enabled = false;

    /** 强制 LIMIT 上限（行）；未指定或超过此值的查询将被 SqlGuardEngine 改写为该值，防止全表扫描 */
    private int defaultRowLimit = 500;

    /** EXPLAIN 估算行数告警阈值；超过则在 warnings 中提示用户该查询代价较大 */
    private long explainRowThreshold = 100_000L;

    /** JdbcTemplate 单次查询超时（秒），含 EXPLAIN 与正式 SELECT；超时后断开连接保护后端 */
    private int executionTimeoutSeconds = 15;

    /** 审批令牌有效期（秒）；超时未确认则 Caffeine 自动驱逐，需要 LLM 重新发起 query_database */
    private int tokenTtlSeconds = 300;

    /** AES-GCM 主密钥原文；建议通过环境变量 {@code SQL_DS_CRYPTO_KEY} 注入，禁止直接写入 yml */
    private String cryptoKey = "";
}
