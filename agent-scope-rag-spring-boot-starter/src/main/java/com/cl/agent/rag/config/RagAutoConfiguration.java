package com.cl.agent.rag.config;

import com.cl.agent.rag.core.DocumentReaderFactory;
import com.cl.agent.rag.core.EmbeddingStoreFactory;
import com.cl.agent.rag.properties.AgentRagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * AgentScope Java RAG 自动装配中心配置类。
 * <p>遵循 Spring Boot Auto-Configuration 工业级规范。
 * 通过配置属性 {@code agent.rag.enabled} 进行条件控制装配。当配置为 {@code true} 时，
 * 动态向 Spring 容器注入多格式文档解析工厂 {@link DocumentReaderFactory} 与向量存储工厂 {@link EmbeddingStoreFactory}，
 * 实现真正的即插即用技术架构。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentRagProperties.class)
@ConditionalOnProperty(prefix = "agent.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class RagAutoConfiguration {

    /**
     * 向 Spring 容器中注册多格式文档解析路由工厂 Bean。
     * <p>使用说明：由宿主系统的 RAG 业务层（如 KnowledgeBizImpl）注入调用。</p>
     *
     * @param properties RAG 自动配置属性 Bean，由容器自动注入，非空
     * @return {@link DocumentReaderFactory} 文档读取工厂实例
     */
    @Bean
    public DocumentReaderFactory documentReaderFactory(AgentRagProperties properties) {
        log.info("[RAG-AutoConfig] 成功装配官方多格式 Reader 路由工厂 DocumentReaderFactory");
        return new DocumentReaderFactory(properties);
    }

    /**
     * 向 Spring 容器中注册向量数据库存储介质构建工厂 Bean。
     * <p>使用说明：由宿主业务层用于初始化官方向量知识库 {@code SimpleKnowledge} 时调用。</p>
     *
     * @param properties RAG 自动配置属性 Bean，由容器自动注入，非空
     * @return {@link EmbeddingStoreFactory} 向量存储库工厂实例
     */
    @Bean
    public EmbeddingStoreFactory embeddingStoreFactory(AgentRagProperties properties) {
        log.info("[RAG-AutoConfig] 成功装配官方向量库路由工厂 EmbeddingStoreFactory, 配置类型: {}", properties.getStoreType());
        return new EmbeddingStoreFactory(properties);
    }
}
