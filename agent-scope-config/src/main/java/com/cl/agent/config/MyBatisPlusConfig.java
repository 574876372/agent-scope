package com.cl.agent.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置类
 */
@Configuration
@MapperScan("com.cl.agent.dao")
public class MyBatisPlusConfig {

    /**
     * 注册自定义 SQL 日志拦截器，打印完整 SQL（参数已填充）
     */
    @Bean
    public SqlLogInterceptor sqlLogInterceptor() {
        return new SqlLogInterceptor();
    }
}
