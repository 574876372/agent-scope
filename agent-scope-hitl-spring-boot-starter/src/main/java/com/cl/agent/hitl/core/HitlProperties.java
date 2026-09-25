package com.cl.agent.hitl.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 人机协同（HITL）框架全局配置属性类。
 * <p>使用说明：在 {@code application.yml} 中配置以 {@code agent.hitl} 为前缀的属性，
 * 比如自定义审批 Token 的有效期。</p>
 */
@Data
@ConfigurationProperties(prefix = "agent.hitl")
public class HitlProperties {

    /** 审批 Token 在内存中的有效期时间（单位：秒），默认 600 秒（10 分钟） */
    private int tokenTtlSeconds = 600;

    /** 内存缓存中允许存放的审批 Token 最大记录上限，默认 10000 条 */
    private int maxCacheSize = 10000;
}
