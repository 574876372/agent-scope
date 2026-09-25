package com.cl.agent.sql.autoconfigure;

import com.cl.agent.sql.core.DialectRouter;
import com.cl.agent.sql.core.QueryCostEstimator;
import com.cl.agent.sql.core.SchemaRetriever;
import com.cl.agent.sql.core.SqlAgentProperties;
import com.cl.agent.sql.core.SqlGuardEngine;
import com.cl.agent.sql.spi.DatasourceProvider;
import com.cl.agent.sql.spi.SqlAuditPublisher;
import com.cl.agent.sql.spi.support.NoOpDatasourceProvider;
import com.cl.agent.sql.spi.support.NoOpSqlAuditPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * SQL Agent starter 自动装配入口。
 *
 * <h2>启用方式</h2>
 * 在宿主 {@code application.yml} 中设置 {@code agent.sql.enabled=true}，否则本 starter 完全惰性，
 * 不注册任何 Bean，不影响宿主原有链路。
 *
 * <h2>Bean 装配顺序</h2>
 * <ol>
 *   <li>{@link SqlAgentProperties}（由 {@code @EnableConfigurationProperties} 注入）</li>
 *   <li>{@link DialectRouter} / {@link SqlGuardEngine}（纯静态依赖）</li>
 *   <li>{@link SchemaRetriever} / {@link QueryCostEstimator}（依赖 properties）</li>
 *   <li>NoOp SPI 兜底（仅当宿主未提供 Bean 时生效）</li>
 *   <li>{@code com.cl.agent.sql.tool} 包下 3 个工具 Bean —— 通过 ComponentScan 自动注册</li>
 * </ol>
 *
 * <h2>与既有 starter 的关系</h2>
 * 与 {@code agent-scope-tool-spring-boot-starter} 并列，不依赖；工具 Bean 通过宿主已有的
 * {@code AgentToolRegistry} 自动扫描，无需新增注册逻辑。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(prefix = "agent.sql", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SqlAgentProperties.class)
@ComponentScan(basePackages = "com.cl.agent.sql.tool")
public class SqlAgentAutoConfiguration {

    /**
     * 方言路由器（默认仅注册 MySQL）。
     *
     * @return DialectRouter 单例
     */
    @Bean
    @ConditionalOnMissingBean
    public DialectRouter dialectRouter() {
        log.info("[SqlAgentAutoConfig] 注册 DialectRouter (mysql)");
        return new DialectRouter();
    }

    /**
     * SQL 守卫引擎（无状态）。
     *
     * @return SqlGuardEngine 单例
     */
    @Bean
    @ConditionalOnMissingBean
    public SqlGuardEngine sqlGuardEngine() {
        return new SqlGuardEngine();
    }

    /**
     * Schema 抽取器（Caffeine 缓存）。
     *
     * @param dialectRouter 方言路由器
     * @param props         配置
     * @return SchemaRetriever 单例
     */
    @Bean
    @ConditionalOnMissingBean
    public SchemaRetriever schemaRetriever(DialectRouter dialectRouter, SqlAgentProperties props) {
        return new SchemaRetriever(dialectRouter, props);
    }

    /**
     * 查询代价估算器（EXPLAIN）。
     *
     * @param dialectRouter 方言路由器
     * @param props         配置
     * @return QueryCostEstimator 单例
     */
    @Bean
    @ConditionalOnMissingBean
    public QueryCostEstimator queryCostEstimator(DialectRouter dialectRouter, SqlAgentProperties props) {
        return new QueryCostEstimator(dialectRouter, props);
    }

    /**
     * 数据源 SPI 兜底实现：宿主未提供 Bean 时生效。
     *
     * @return NoOpDatasourceProvider 单例
     */
    @Bean
    @ConditionalOnMissingBean(DatasourceProvider.class)
    public DatasourceProvider noOpDatasourceProvider() {
        log.warn("[SqlAgentAutoConfig] 未发现宿主 DatasourceProvider Bean，注册 NoOp 兜底实现");
        return new NoOpDatasourceProvider();
    }

    /**
     * 审计 SPI 兜底实现：宿主未提供 Bean 时生效，仅打 INFO 日志。
     *
     * @return NoOpSqlAuditPublisher 单例
     */
    @Bean
    @ConditionalOnMissingBean(SqlAuditPublisher.class)
    public SqlAuditPublisher noOpSqlAuditPublisher() {
        log.warn("[SqlAgentAutoConfig] 未发现宿主 SqlAuditPublisher Bean，注册 NoOp 兜底实现");
        return new NoOpSqlAuditPublisher();
    }
}
