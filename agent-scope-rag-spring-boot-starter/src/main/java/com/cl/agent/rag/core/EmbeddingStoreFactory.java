package com.cl.agent.rag.core;

import com.cl.agent.rag.properties.AgentRagProperties;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.rag.store.MilvusStore;
import io.agentscope.core.rag.store.ElasticsearchStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 向量存储库（Vector Database Store）动态路由构建工厂。
 * <p>读取 {@link AgentRagProperties} 配置的向量介质选项（IN_MEMORY/MILVUS/ELASTICSEARCH），
 * 为特定的物理知识库实例分配对应的 {@link VDBStoreBase} 存储 Bean，确保多租户与库级物理安全隔离。</p>
 */
@Slf4j
@Component
public class EmbeddingStoreFactory {

    /** RAG 属性配置项 */
    private final AgentRagProperties properties;

    /** 默认的向量维度（对应 OpenAI text-embedding-3-small 或 Qwen text-embedding-v3） */
    private static final int DEFAULT_DIMENSIONS = 1536;

    /**
     * 构造向量库生成工厂。
     *
     * @param properties RAG 配置属性对象，非空
     */
    public EmbeddingStoreFactory(AgentRagProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据当前系统配置的 `storeType` 动态构建对应的 AgentScope 官方向量存储实例。
     * <p>使用说明：在创建/加载 Agent 所关联的官方知识库 {@code SimpleKnowledge} 时调用。
     * 对外部向量库（如 Milvus / ES），以知识库唯一标识 {@code kbId} 进行库/索引级的物理命名划分，实现严格的数据隔离。</p>
     *
     * @param kbId 知识库唯一 ID，用于建立隔离存储的分区/集合名称，必填且非空
     * @return {@link VDBStoreBase} 配置并初始化完毕的官方向量数据库存储实例
     * @throws RuntimeException 当构建官方向量数据库连接或初始化失败（例如 VectorStoreException 异常）时抛出
     */
    public VDBStoreBase createStore(String kbId) {
        String type = properties.getStoreType().trim().toUpperCase();
        log.info("[RAG-VDB] 正在构建向量存储介质: type={}, kbId={}", type, kbId);

        try {
            switch (type) {
                case "MILVUS":
                    String milvusCollection = properties.getMilvus().getCollectionName() + "_" + kbId.replace("-", "_");
                    log.info("[RAG-VDB] 构建官方 Milvus 向量存储: uri={}, collection={}", 
                            properties.getMilvus().getUri(), milvusCollection);
                    return MilvusStore.builder()
                            .uri(properties.getMilvus().getUri())
                            .collectionName(milvusCollection)
                            .token(properties.getMilvus().getToken())
                            .build();

                case "ELASTICSEARCH":
                    String esIndex = (properties.getElasticsearch().getIndexName() + "_" + kbId).toLowerCase();
                    log.info("[RAG-VDB] 构建官方 Elasticsearch 向量存储: uris={}, index={}", 
                            properties.getElasticsearch().getUris(), esIndex);
                    String esUrl = properties.getElasticsearch().getUris() == null || properties.getElasticsearch().getUris().isEmpty() ? 
                            "http://localhost:9200" : properties.getElasticsearch().getUris().get(0);
                    return ElasticsearchStore.builder()
                            .url(esUrl)
                            .indexName(esIndex)
                            .username(properties.getElasticsearch().getUsername())
                            .password(properties.getElasticsearch().getPassword())
                            .dimensions(DEFAULT_DIMENSIONS)
                            .build();

                case "IN_MEMORY":
                default:
                    log.info("[RAG-VDB] 构建本地内存型向量存储 (开发测试), dimensions={}", DEFAULT_DIMENSIONS);
                    return InMemoryStore.builder()
                            .dimensions(DEFAULT_DIMENSIONS)
                            .build();
            }
        } catch (Exception e) {
            log.error("[RAG-VDB] 构建向量存储介质失败: type={}, kbId={}", type, kbId, e);
            throw new RuntimeException("构建向量存储介质失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据指定的模型提供商与配置，构建 OpenAI 兼容标准接口的 EmbeddingModel 实例。
     * <p>使用说明：初始化 {@code SimpleKnowledge} 知识库实例时需要注入对应的 Embedding 引擎进行文本向量计算。</p>
     *
     * @param apiKey  大模型接口鉴权 Token 密钥，必填且非空
     * @param baseUrl 大模型接口中转/厂商 Base URL 基础地址，必填且非空
     * @return {@link EmbeddingModel} 实例化完成的标准向量计算模型引擎
     */
    public EmbeddingModel createEmbeddingModel(String apiKey, String baseUrl) {
        String modelName = "text-embedding-3-small";
        boolean isQwen = baseUrl != null && (baseUrl.contains("oldbird.tech") || baseUrl.contains("aliyuncs.com") || baseUrl.contains("dashscope"));
        if (isQwen) {
            modelName = "text-embedding-v2";
        }
        log.info("[RAG-Model] 正在构建 OpenAITextEmbedding 客户端: baseUrl={}, modelName={}", baseUrl, modelName);
        if (isQwen) {
            return OpenAITextEmbedding.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .build();
        } else {
            return OpenAITextEmbedding.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .dimensions(DEFAULT_DIMENSIONS)
                    .build();
        }
    }
}
