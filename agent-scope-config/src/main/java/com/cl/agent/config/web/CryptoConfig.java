package com.cl.agent.config.web;

import com.cl.agent.commons.crypto.CryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局加解密服务配置
 * <p>注册 {@link CryptoService} 单例，供数据源密码、模型厂商密钥等敏感字段统一加解密。
 * 密钥来自 {@code agent.crypto-key}；未配置时服务仍会注册，首次加解密时报错提示。</p>
 */
@Configuration
public class CryptoConfig {

    @Bean
    public CryptoService cryptoService(@Value("${agent.crypto-key:}") String cryptoKey) {
        return new CryptoService(cryptoKey);
    }
}
