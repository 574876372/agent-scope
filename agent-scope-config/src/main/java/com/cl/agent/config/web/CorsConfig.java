package com.cl.agent.config.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 全局跨域（CORS）配置
 * <p>通过 {@code agent.web.cors.enabled=true} 开启，替代各 Controller 上的 {@code @CrossOrigin}。</p>
 * <p>采用 Servlet 层 {@link CorsFilter} 并设为最高优先级，而非 {@code WebMvcConfigurer#addCorsMappings}：
 * OPTIONS 预检在 Filter 层直接应答，不经过 {@link AuthInterceptor}；
 * 由其它 Filter 或异常直接返回的错误响应同样带有 CORS 头，前端可拿到真实错误信息。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "agent.web.cors", name = "enabled", havingValue = "true")
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(properties.getAllowedOriginPatterns());
        config.setAllowedMethods(properties.getAllowedMethods());
        config.setAllowedHeaders(properties.getAllowedHeaders());
        config.setExposedHeaders(properties.getExposedHeaders());
        config.setAllowCredentials(properties.isAllowCredentials());
        config.setMaxAge(properties.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(properties.getPathPattern(), config);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setName("globalCorsFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);

        log.info("[CORS] 全局跨域已开启: path={}, origins={}, credentials={}, maxAge={}s",
                properties.getPathPattern(), properties.getAllowedOriginPatterns(),
                properties.isAllowCredentials(), properties.getMaxAge());
        return registration;
    }
}
