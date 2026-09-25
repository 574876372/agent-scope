package com.cl.agent.rag.core;

import com.cl.agent.rag.properties.AgentRagProperties;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;
import io.agentscope.core.rag.store.ElasticsearchStore;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.store.MilvusStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量存储介质与 Embedding 模型的统一构建工厂。
 * <p>
 * 职责：
 * </p>
 * <ul>
 * <li>按调用方传入的 {@link EmbeddingModelSpec} 构建 {@link EmbeddingModel}，以 modelId 为键缓存；
 * spec 的 version 变化（如更换密钥）时自动重建。模型参数从何而来由宿主决定，starter 不访问数据库；</li>
 * <li>按 {@code agent.rag.store-type} 为每个知识库构建 {@link VDBStoreBase}，并以 kbId 为键缓存，
 * 保证入库线程与检索线程拿到同一个存储实例（对 {@code IN_MEMORY} 尤为关键，否则检索端看不到入库端写入的向量）。
 * 索引维度由知识库绑定的向量模型决定。</li>
 * </ul>
 * <p>
 * 实现 {@link AutoCloseable}，Spring 容器销毁时会自动调用 {@link #close()} 释放 ES / Milvus
 * 连接。
 * </p>
 */
@Slf4j
public class EmbeddingStoreFactory implements AutoCloseable {

    private static final String STORE_IN_MEMORY = "IN_MEMORY";
    private static final String STORE_MILVUS = "MILVUS";
    private static final String STORE_ELASTICSEARCH = "ELASTICSEARCH";

    private static final String PROTOCOL_OPENAI = "OPENAI";

    private final AgentRagProperties properties;

    /** 以 kbId 为键缓存的向量存储实例 */
    private final Map<String, VDBStoreBase> storeCache = new ConcurrentHashMap<>();

    /** 以 modelId 为键缓存的 Embedding 客户端，附带构建时的 spec 版本 */
    private final Map<String, CachedModel> modelCache = new ConcurrentHashMap<>();

    public EmbeddingStoreFactory(AgentRagProperties properties) {
        this.properties = properties;
    }

    // ========================================================
    // Embedding 模型
    // ========================================================

    /**
     * 获取指定模型的 Embedding 客户端（带缓存）。
     * <p>
     * 同一 modelId 且 version 未变时复用缓存实例；version 变化时重建并替换旧实例。
     * </p>
     *
     * @param spec 模型参数，modelId 与 version 非空
     * @return {@link EmbeddingModel} 实例，线程安全，可重复使用
     * @throws IllegalStateException 参数缺失或协议不支持时抛出
     */
    public EmbeddingModel getEmbeddingModel(EmbeddingModelSpec spec) {
        requireConfigured(spec.getModelId(), "modelId");
        String version = spec.getVersion() == null ? "" : spec.getVersion();
        CachedModel cached = modelCache.compute(spec.getModelId(), (id, current) ->
                current != null && current.version.equals(version)
                        ? current
                        : new CachedModel(version, createEmbeddingModel(spec)));
        return cached.model;
    }

    /**
     * 按参数新建一个 Embedding 客户端，不进入缓存；可用于"测试连接"等一次性场景。
     *
     * @param spec 模型参数
     * @return 新建的 {@link EmbeddingModel}
     * @throws IllegalStateException 参数缺失或协议不支持时抛出
     */
    public EmbeddingModel createEmbeddingModel(EmbeddingModelSpec spec) {
        requireConfigured(spec.getBaseUrl(), "baseUrl");
        requireConfigured(spec.getApiKey(), "apiKey");
        requireConfigured(spec.getModelName(), "modelName");
        if (spec.getDimensions() <= 0) {
            throw new IllegalStateException("Embedding 模型参数缺失: dimensions 必须为正整数");
        }
        String protocol = isBlank(spec.getProtocol()) ? PROTOCOL_OPENAI : spec.getProtocol().trim().toUpperCase();
        if (!PROTOCOL_OPENAI.equals(protocol)) {
            throw new IllegalStateException("暂不支持的 Embedding 接口协议: " + protocol);
        }

        log.info("[RAG-Model] 构建 Embedding 客户端: {}", spec);
        OpenAITextEmbedding.Builder builder = OpenAITextEmbedding.builder()
                .apiKey(spec.getApiKey())
                .baseUrl(spec.getBaseUrl())
                .modelName(spec.getModelName());
        if (spec.isSendDimensions()) {
            builder.dimensions(spec.getDimensions());
        }
        return builder.build();
    }

    // ========================================================
    // 向量存储
    // ========================================================

    /**
     * 当前是否使用进程内内存向量库。
     * <p>
     * 内存库不跨进程、不持久化，调用方需据此决定是否从关系库回灌切片做预热。
     * </p>
     */
    public boolean isInMemoryStore() {
        return STORE_IN_MEMORY.equals(normalizedStoreType());
    }

    /**
     * 获取（或按需创建）指定知识库的向量存储实例。
     * <p>
     * 同一 kbId 在整个进程生命周期内返回同一实例：ES / Milvus 复用底层连接，
     * IN_MEMORY 则保证入库与检索共享同一份内存数据。
     * </p>
     *
     * @param kbId       知识库 ID，非空
     * @param dimensions 知识库绑定的向量模型维度，仅在首次创建存储实例时用于定义索引
     * @return {@link VDBStoreBase} 向量存储实例
     * @throws RuntimeException 底层存储初始化失败时抛出
     */
    public VDBStoreBase getStore(String kbId, int dimensions) {
        return storeCache.computeIfAbsent(kbId, id -> buildStore(id, dimensions));
    }

    /**
     * 移除并关闭指定知识库的向量存储实例。
     * <p>
     * IN_MEMORY 模式下等价于清空该知识库的全部向量，下次 {@link #getStore(String, int)} 会得到一个空库；
     * ES / Milvus 模式下仅释放连接，远端数据不受影响。
     * </p>
     *
     * @param kbId 知识库 ID
     */
    public void evictStore(String kbId) {
        VDBStoreBase removed = storeCache.remove(kbId);
        if (removed != null) {
            log.info("[RAG-VDB] 已移除向量存储实例缓存: type={}, kbId={}", normalizedStoreType(), kbId);
            closeQuietly(removed);
        }
    }

    private VDBStoreBase buildStore(String kbId, int dimensions) {
        String type = normalizedStoreType();
        log.info("[RAG-VDB] 正在构建向量存储介质: type={}, kbId={}, dimensions={}", type, kbId, dimensions);

        try {
            switch (type) {
                case STORE_MILVUS:
                    String milvusCollection = properties.getMilvus().getCollectionName() + "_" + kbId.replace("-", "_");
                    log.info("[RAG-VDB] 构建官方 Milvus 向量存储: uri={}, collection={}",
                            properties.getMilvus().getUri(), milvusCollection);
                    return MilvusStore.builder()
                            .uri(properties.getMilvus().getUri())
                            .collectionName(milvusCollection)
                            .token(properties.getMilvus().getToken())
                            .dimensions(dimensions)
                            .build();

                case STORE_ELASTICSEARCH:
                    String esIndex = (properties.getElasticsearch().getIndexName() + "_" + kbId).toLowerCase();
                    String esUrl = properties.getElasticsearch().getUris() == null
                            || properties.getElasticsearch().getUris().isEmpty()
                                    ? "http://localhost:9200"
                                    : properties.getElasticsearch().getUris().get(0);
                    log.info("[RAG-VDB] 构建官方 Elasticsearch 向量存储: url={}, index={}", esUrl, esIndex);
                    return ElasticsearchStore.builder()
                            .url(esUrl)
                            .indexName(esIndex)
                            .username(properties.getElasticsearch().getUsername())
                            .password(properties.getElasticsearch().getPassword())
                            .dimensions(dimensions)
                            .build();

                case STORE_IN_MEMORY:
                default:
                    log.info("[RAG-VDB] 构建本地内存型向量存储 (开发测试), dimensions={}", dimensions);
                    return InMemoryStore.builder()
                            .dimensions(dimensions)
                            .build();
            }
        } catch (Exception e) {
            log.error("[RAG-VDB] 构建向量存储介质失败: type={}, kbId={}", type, kbId, e);
            throw new RuntimeException("构建向量存储介质失败: " + e.getMessage(), e);
        }
    }

    private String normalizedStoreType() {
        String type = properties.getStoreType();
        return type == null ? STORE_IN_MEMORY : type.trim().toUpperCase();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void requireConfigured(String value, String key) {
        if (isBlank(value)) {
            throw new IllegalStateException("Embedding 模型参数缺失: " + key + " 未配置");
        }
    }

    private static void closeQuietly(VDBStoreBase store) {
        if (store instanceof AutoCloseable) {
            try {
                ((AutoCloseable) store).close();
            } catch (Exception e) {
                log.warn("[RAG-VDB] 关闭向量存储连接失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 释放所有缓存的向量存储连接与 Embedding 客户端。由 Spring 容器在销毁 Bean 时自动调用。
     */
    @Override
    public void close() {
        storeCache.values().forEach(EmbeddingStoreFactory::closeQuietly);
        storeCache.clear();
        modelCache.clear();
    }

    /** 缓存的 Embedding 客户端及其构建时的 spec 版本 */
    private static final class CachedModel {
        private final String version;
        private final EmbeddingModel model;

        private CachedModel(String version, EmbeddingModel model) {
            this.version = version;
            this.model = model;
        }
    }
}
