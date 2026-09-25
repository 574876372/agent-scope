package com.cl.agent.hitl.autoconfigure;

import com.cl.agent.hitl.core.GenericApprovalTokenStore;
import com.cl.agent.hitl.core.HitlProperties;
import com.cl.agent.hitl.core.ToolApprovalInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 人机协同（HITL）框架自动装配配置类。
 * <p>使用说明：Spring Boot 启动时自动扫描加载，装配通用的 Token 存储管理器及 AOP/反射工具拦截器。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(HitlProperties.class)
public class HitlAutoConfiguration {

    /**
     * 声明通用审批 Token 存储管理器。
     *
     * @param properties 配置属性，非空
     * @return GenericApprovalTokenStore 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public GenericApprovalTokenStore genericApprovalTokenStore(HitlProperties properties) {
        return new GenericApprovalTokenStore(properties);
    }

    /**
     * 声明敏感工具人机协同拦截器。
     *
     * @param tokenStore Token 存储管理器，非空
     * @return ToolApprovalInterceptor 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolApprovalInterceptor toolApprovalInterceptor(GenericApprovalTokenStore tokenStore) {
        return new ToolApprovalInterceptor(tokenStore);
    }
}
