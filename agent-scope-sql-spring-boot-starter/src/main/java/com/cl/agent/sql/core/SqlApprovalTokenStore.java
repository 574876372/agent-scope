package com.cl.agent.sql.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL 审批令牌存储。
 *
 * <h2>语义</h2>
 * <ul>
 *   <li><b>颁发</b>：{@link #issue} 由 {@code QueryDatabaseTool} 在守卫通过后调用，返回一次性 token。</li>
 *   <li><b>消费</b>：{@link #take} 由 {@code SqlConfirmExecutor} 调用，立即 invalidate，防止重放。</li>
 *   <li><b>预览</b>：{@link #peek} 只读，不消费，仅供审计 / 调试。</li>
 * </ul>
 *
 * <h2>实现</h2>
 * 内置 Caffeine 缓存，TTL 由 {@code agent.sql.token-ttl-seconds} 控制（默认 300s）。
 * 单节点内存即可，跨节点场景未来可替换为 Redis 实现（保持接口签名不变）。
 */
@Slf4j
public class SqlApprovalTokenStore {

    /** Caffeine cache：token → 审批上下文 */
    private final Cache<String, SqlApprovalContext> cache;

    /** TTL 秒数，由配置注入 */
    private final int ttlSeconds;

    /**
     * 构造方法。
     *
     * @param props 全局配置；当前仅使用 {@link SqlAgentProperties#getTokenTtlSeconds()}
     */
    public SqlApprovalTokenStore(SqlAgentProperties props) {
        Objects.requireNonNull(props, "props");
        this.ttlSeconds = props.getTokenTtlSeconds();
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(10_000)
                .removalListener((key, value, cause) -> {
                    if (cause.wasEvicted()) {
                        log.debug("[ApprovalTokenStore] token {} 被驱逐, cause={}", key, cause);
                    }
                })
                .build();
        log.info("[ApprovalTokenStore] 初始化完成: ttl={}s, maxSize=10000", ttlSeconds);
    }

    /**
     * 颁发一次性 token，并保存关联的 SQL 上下文。
     *
     * @param userId         用户 ID，必填
     * @param conversationId 会话 ID，可空
     * @param datasourceId   数据源 ID，必填
     * @param sanitizedSql   经过守卫的 SQL，必填
     * @param estimatedRows  EXPLAIN 估算的扫描行数，可传 -1 表示未估算
     * @return 一次性 token 字符串
     */
    public String issue(String userId, String conversationId, String datasourceId,
                        String sanitizedSql, long estimatedRows) {
        String token = "tok-" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        SqlApprovalContext ctx = SqlApprovalContext.builder()
                .token(token)
                .userId(userId)
                .conversationId(conversationId)
                .datasourceId(datasourceId)
                .sql(sanitizedSql)
                .estimatedRows(estimatedRows)
                .createdAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .build();
        cache.put(token, ctx);
        return token;
    }

    /**
     * 一次性消费 token：取出上下文并立即从缓存移除。
     *
     * <p>失败场景（token 不存在 / 已过期 / 已消费）返回 {@link Optional#empty()}；调用方需自行处理。</p>
     *
     * @param token 颁发的 token，必填
     * @return 上下文 Optional
     */
    public Optional<SqlApprovalContext> take(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        SqlApprovalContext ctx = cache.getIfPresent(token);
        if (ctx != null) {
            cache.invalidate(token);
        }
        return Optional.ofNullable(ctx);
    }

    /**
     * 只读预览 token 对应的上下文（**不**消费）。
     *
     * @param token token
     * @return 上下文 Optional
     */
    public Optional<SqlApprovalContext> peek(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.getIfPresent(token));
    }

    /**
     * 主动失效 token（例如用户主动取消时立即清理）。
     *
     * @param token token
     */
    public void invalidate(String token) {
        if (token != null && !token.isBlank()) {
            cache.invalidate(token);
        }
    }
}
