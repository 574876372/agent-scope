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
     * 默认向量召回相似度预过滤阈值。
     * <p>取值范围 0.0 ~ 1.0，默认为 0.3；低于该分数的向量结果不参与融合，关键词召回不受影响。
     * 不同向量模型的分数分布不同，最终排序按融合排名而非原始分数。可由 Agent 配置单独覆盖。</p>
     */
    private double defaultScoreThreshold = 0.3;

    /**
     * 文档切片的最大字符数。
     * <p>默认为 512；知识库级配置与类型预设均未指定时使用。</p>
     */
    private int chunkSize = 512;

    /**
     * 超长段落按句拆分时相邻切片的重叠字符数。
     * <p>默认为 80；结构化切片只在单个段落超过切片上限时才产生重叠。</p>
     */
    private int chunkOverlap = 80;

    /**
     * 入库时每次 Embedding 请求携带的切片数。
     * <p>默认为 10（通义 text-embedding-v3 的上限，v2 可调到 25）；厂商不支持数组输入时自动退化为逐条请求。</p>
     */
    private int embeddingBatchSize = 10;

    /**
     * 检索流水线全局默认参数（召回数、融合、上下文扩展、查询改写等）。
     */
    private final RetrievalProperties retrieval = new RetrievalProperties();

    /**
     * 知识库上传文档的本地存储根目录。
     * <p>实际路径为 {@code {uploadDir}/{知识库ID}/yyyy/MM/dd/{文档ID}.{扩展名}}；
     * 相对路径以后端进程的启动目录为基准，生产环境建议配置绝对路径。</p>
     */
    private String uploadDir = "./data/uploads";

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
