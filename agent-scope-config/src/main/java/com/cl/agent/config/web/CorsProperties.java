package com.cl.agent.config.web;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局跨域（CORS）配置属性
 * <p>对应 {@code application.yml} 中的 {@code agent.web.cors.*} 配置段，由 {@link CorsConfig} 读取并注册全局 CorsFilter。</p>
 *
 * <pre>
 * agent:
 *   web:
 *     cors:
 *       enabled: true
 *       allowed-origin-patterns:
 *         - http://localhost:*
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.web.cors")
public class CorsProperties {

    /** 跨域总开关；未配置时默认关闭 */
    private boolean enabled = false;

    /** CORS 生效的路径模式 */
    private String pathPattern = "/**";

    /** 允许的来源，支持通配（如 {@code http://localhost:*}）；为空时不放行任何跨域来源 */
    private List<String> allowedOriginPatterns = new ArrayList<>();

    /** 允许的 HTTP 方法 */
    private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));

    /** 允许的请求头；默认 {@code *}，包含自定义的 {@code X-User-Id} */
    private List<String> allowedHeaders = new ArrayList<>(List.of("*"));

    /** 允许前端 JS 读取的响应头（如文件下载的 {@code Content-Disposition}） */
    private List<String> exposedHeaders = new ArrayList<>();

    /** 是否允许携带 Cookie 等凭证；当前身份通过请求头传递，默认关闭 */
    private boolean allowCredentials = false;

    /** 预检请求结果的缓存时长（秒），减少 OPTIONS 请求次数 */
    private long maxAge = 3600L;
}
