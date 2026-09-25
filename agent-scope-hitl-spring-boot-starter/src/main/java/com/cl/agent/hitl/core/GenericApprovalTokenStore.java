package com.cl.agent.hitl.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 通用审批令牌存储器。
 * <p>使用说明：采用本地内存缓存（Caffeine）管理生成的临时审批 Token 与 {@link ApprovalContext}。
 * 提供 Token 的颁发、消费（一次性，消费后即失效）、预览和主动清除功能。</p>
 */
@Slf4j
public class GenericApprovalTokenStore {

    /** 内存 Caffeine 缓存容器 */
    private final Cache<String, ApprovalContext> cache;

    /** 审批 Token 有效期（秒） */
    private final int ttlSeconds;

    /**
     * 构造令牌存储器。
     * <p>使用说明：由 {@link com.cl.agent.hitl.autoconfigure.HitlAutoConfiguration} 自动创建并注入配置属性对象。</p>
     *
     * @param props HITL 全局配置属性，非空
     */
    public GenericApprovalTokenStore(HitlProperties props) {
        Objects.requireNonNull(props, "props");
        this.ttlSeconds = props.getTokenTtlSeconds();
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(props.getMaxCacheSize())
                .removalListener((key, value, cause) -> {
                    if (cause.wasEvicted()) {
                        log.debug("[HITL-TokenStore] Token {} 被移除/过期驱逐, 原因={}", key, cause);
                    }
                })
                .build();
        log.info("[HITL-TokenStore] 令牌管理器初始化成功: ttl={}s, maxSize={}", ttlSeconds, props.getMaxCacheSize());
    }

    /**
     * 颁发并注册一个新的审批 Token 上下文。
     * <p>使用说明：当拦截器拦截到敏感工具调用时，调用此方法颁发 Token 并保存上下文。</p>
     *
     * @param userId          提交请求的用户 ID，非空
     * @param conversationId  会话 ID，可空
     * @param toolName        工具名称，非空
     * @param parameters      大模型传入的参数，非空
     * @param parameterSchema 参数定义 JSON Schema，非空
     * @param preCheckMeta    预检元数据，可为空
     * @return 颁发的一次性 Token 字符串，格式为 {@code tok_gen_xxxx}
     */
    public String issue(String userId, String conversationId, String toolName,
                        Map<String, Object> parameters, Map<String, Object> parameterSchema,
                        Map<String, Object> preCheckMeta) {
        String token = "tok_gen_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        ApprovalContext ctx = ApprovalContext.builder()
                .token(token)
                .userId(userId)
                .conversationId(conversationId)
                .toolName(toolName)
                .parameters(parameters)
                .parameterSchema(parameterSchema)
                .preCheckMeta(preCheckMeta)
                .createdAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .build();
        cache.put(token, ctx);
        log.debug("[HITL-TokenStore] 颁发审批令牌: token={}, toolName={}, userId={}", token, toolName, userId);
        return token;
    }

    /**
     * 一次性消费审批 Token：从缓存中读取后立即失效删除，规避重放攻击。
     * <p>使用说明：当人类在前端点击同意/执行，发送二次处理请求时被执行器消费调用。</p>
     *
     * @param token 待消费的 Token 字符串，非空
     * @return 返回 {@link Optional} 包装的审批上下文；若 Token 不存在、过期或已被消费，则返回空 {@link Optional#empty()}
     */
    public Optional<ApprovalContext> take(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        ApprovalContext ctx = cache.getIfPresent(token);
        if (ctx != null) {
            cache.invalidate(token);
            log.debug("[HITL-TokenStore] 成功消费并置失效令牌: token={}", token);
        }
        return Optional.ofNullable(ctx);
    }

    /**
     * 只读方式预览 Token 的详细上下文，不会发生消费或失效。
     * <p>使用说明：仅供审计、日志记录或状态检查等只读场景调用。</p>
     *
     * @param token 目标 Token，非空
     * @return 返回 {@link Optional} 包装的审批上下文；若不存在或已过期则返回空
     */
    public Optional<ApprovalContext> peek(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.getIfPresent(token));
    }

    /**
     * 强行失效清除指定的 Token。
     * <p>使用说明：当用户在前端主动点击“取消/拒绝”时调用，提前释放并清理内存占用。</p>
     *
     * @param token 待失效的 Token 字符串，可空
     */
    public void invalidate(String token) {
        if (token != null && !token.isBlank()) {
            cache.invalidate(token);
            log.debug("[HITL-TokenStore] 手动清除令牌: token={}", token);
        }
    }
}
