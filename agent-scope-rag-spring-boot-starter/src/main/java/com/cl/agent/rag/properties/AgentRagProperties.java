package com.cl.agent.rag.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

/**
 * RAG 模块自动配置属性映射类。
 * <p>用于从配置源（如 application.yml）读取并绑定以 {@code agent.rag} 为前缀的所有配置项，
 * 支持动态切换向量库类型（IN_MEMORY/MILVUS/ELASTICSEARCH）及自定义连接细节。</p>
 */
@ConfigurationProperties(prefix = "agent.rag")
@Data
public class AgentRagProperties {

    /**
     * 是否启用 RAG Starter 模块。
     * <p>默认为 {@code true}，控制配置类及核心 RAG 基础设施 Bean 是否自动注入容器。</p>
     */
    private boolean enabled = true;

    /**
     * 向量数据库介质类型。
     * <p>可选值：{@code IN_MEMORY}（本地内存库）、{@code MILVUS}（企业级高可用）、{@code ELASTICSEARCH}（基于 ES 索引）、{@code QDRANT}。</p>
     */
    private String storeType = "IN_MEMORY";

    /**
     * 默认单次检索召回的最大文本分片数量（Top-K）。
     * <p>默认为 3 片，可由特定 Agent 配置单独覆盖。</p>
     */
    private int defaultRowLimit = 3;

    /**
     * 默认检索相似度得分最低过滤阈值。
     * <p>取值范围 0.0 ~ 1.0，默认为 0.3，仅召回大于或等于该分数的片段。</p>
     */
    private double defaultScoreThreshold = 0.3;

    /**
     * 文档切片分割的最大字数/字符尺寸。
     * <p>默认为 512，控制物理文档解析切块的大小。</p>
     */
    private int chunkSize = 512;

    /**
     * 内存向量存储配置。
     */
    private final InMemoryProperties inMemory = new InMemoryProperties();

    /**
     * Milvus 向量数据库配置。
     */
    private final MilvusProperties milvus = new MilvusProperties();

    /**
     * Elasticsearch 搜索引擎向量存储配置。
     */
    private final ElasticsearchProperties elasticsearch = new ElasticsearchProperties();
}
