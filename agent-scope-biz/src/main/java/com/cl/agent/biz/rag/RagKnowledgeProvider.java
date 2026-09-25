package com.cl.agent.biz.rag;

import com.cl.agent.dto.model.ModelConnection;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.KnowledgeBase;
import com.cl.agent.model.KnowledgeChunk;
import com.cl.agent.model.ModelInfo;
import com.cl.agent.rag.core.EmbeddingModelSpec;
import com.cl.agent.rag.core.EmbeddingStoreFactory;
import com.cl.agent.rag.properties.AgentRagProperties;
import com.cl.agent.service.IKnowledgeService;
import com.cl.agent.service.IModelConfigService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库运行时 {@link SimpleKnowledge} 统一提供者。
 * <p>业务层（文档入库、Playground 检索、Agent 构建）获取 Knowledge 的唯一入口，屏蔽三件事：</p>
 * <ul>
 *   <li>Embedding 模型与向量存储的装配：按知识库绑定的向量模型从数据库解析连接参数，
 *       委托 {@link EmbeddingStoreFactory} 构建（同一 kbId 始终得到同一存储实例，密钥变更后客户端自动重建）；</li>
 *   <li>{@code IN_MEMORY} 模式下进程重启后的懒加载预热：首次访问某知识库时从 MySQL 切片表回灌向量，
 *       ES / Milvus 等进程外存储不做任何预热，检索直接命中远端已有向量；</li>
 *   <li>文档 / 知识库删除时向量库的同步清理。</li>
 * </ul>
 * <p>RAG 模块未启用（{@code agent.rag.enabled=false}）时本组件仍会注册，但 {@link #isEnabled()} 返回 {@code false}，
 * 调用 {@link #getKnowledge(String)} 会抛出 {@link BizException}。</p>
 */
@Slf4j
@Component
public class RagKnowledgeProvider {

    @Autowired
    private IKnowledgeService knowledgeService;

    @Autowired(required = false)
    private EmbeddingStoreFactory embeddingStoreFactory;

    @Autowired(required = false)
    private AgentRagProperties ragProperties;

    @Autowired
    private IModelConfigService modelConfigService;

    /** 系统默认召回上限，配置缺失时的兜底值 */
    private static final int FALLBACK_RECALL_LIMIT = 3;

    /** 系统默认相似度阈值，配置缺失时的兜底值 */
    private static final double FALLBACK_SCORE_THRESHOLD = 0.3;

    /**
     * RAG 基础设施是否可用。
     */
    public boolean isEnabled() {
        return embeddingStoreFactory != null;
    }

    /**
     * 获取指定知识库的运行时 Knowledge 实例。
     * <p>返回的 {@link SimpleKnowledge} 本身是轻量包装，可按需创建；其内部的存储实例由工厂缓存并跨调用复用。</p>
     *
     * @param kbId 知识库 ID，非空
     * @return {@link SimpleKnowledge}，已绑定该知识库的向量模型与向量存储
     * @throws BizException RAG 模块未启用、知识库不存在或其向量模型不可用（停用、缺少密钥）时抛出
     */
    public SimpleKnowledge getKnowledge(String kbId) {
        ensureEnabled();
        // 每次都按主键查一次最新配置，由 version 判断缓存的客户端是否需要重建，多实例部署下同样能感知配置变更
        ModelConnection conn = modelConfigService.resolveEmbeddingConnection(requireKnowledgeBase(kbId).getEmbeddingModelId());
        VDBStoreBase store = embeddingStoreFactory.getStore(kbId, conn.getDimensions());
        SimpleKnowledge knowledge = SimpleKnowledge.builder()
                .embeddingModel(embeddingStoreFactory.getEmbeddingModel(toSpec(conn)))
                .embeddingStore(store)
                .build();
        if (store instanceof InMemoryStore) {
            warmUpInMemoryStore(kbId, knowledge, (InMemoryStore) store);
        }
        return knowledge;
    }

    /**
     * 全局默认召回上限（Top-K），来自 {@code agent.rag.default-row-limit}。
     */
    public int getDefaultRecallLimit() {
        return ragProperties != null ? ragProperties.getDefaultRowLimit() : FALLBACK_RECALL_LIMIT;
    }

    /**
     * 全局默认相似度过滤阈值，来自 {@code agent.rag.default-score-threshold}。
     */
    public double getDefaultScoreThreshold() {
        return ragProperties != null ? ragProperties.getDefaultScoreThreshold() : FALLBACK_SCORE_THRESHOLD;
    }

    /**
     * 删除某个文档在向量库中的全部切片向量。
     * <p>须在 MySQL 切片记录删除之前调用，因为需要用切片表中的 id（即入库时的向量文档 id）逐条删除。
     * {@code IN_MEMORY} 模式下直接丢弃该知识库的整个内存库，下次访问时从 MySQL 重新预热。</p>
     *
     * @param kbId  文档所属知识库 ID
     * @param docId 文档 ID
     */
    public void removeDocumentVectors(String kbId, String docId) {
        if (!isEnabled()) {
            return;
        }
        if (embeddingStoreFactory.isInMemoryStore()) {
            embeddingStoreFactory.evictStore(kbId);
            log.info("[RAG-Vector] 内存向量库已整体丢弃，将在下次访问时重建: kbId={}, docId={}", kbId, docId);
            return;
        }

        List<KnowledgeChunk> chunks = knowledgeService.listChunksByDocId(docId);
        if (chunks.isEmpty()) {
            return;
        }
        VDBStoreBase store = embeddingStoreFactory.getStore(kbId, resolveDimensions(kbId));
        int deleted = 0;
        for (KnowledgeChunk chunk : chunks) {
            try {
                Boolean ok = store.delete(chunk.getId()).block();
                if (Boolean.TRUE.equals(ok)) {
                    deleted++;
                } else {
                    // 早期版本入库的切片 id 与向量文档 id 不一致，远端找不到对应向量，只能记录告警
                    log.warn("[RAG-Vector] 向量库中未找到切片，可能为旧数据: kbId={}, docId={}, chunkId={}",
                            kbId, docId, chunk.getId());
                }
            } catch (Exception e) {
                log.warn("[RAG-Vector] 删除切片向量失败: kbId={}, chunkId={}, reason={}", kbId, chunk.getId(), e.getMessage());
            }
        }
        log.info("[RAG-Vector] 文档向量清理完成: kbId={}, docId={}, 切片总数={}, 实际删除={}", kbId, docId, chunks.size(), deleted);
    }

    /**
     * 知识库整体删除后的收尾：释放该知识库的向量存储实例（连接或内存）。
     * <p>调用前应已通过 {@link #removeDocumentVectors} 逐个清理文档向量。</p>
     *
     * @param kbId 知识库 ID
     */
    public void releaseKnowledgeBase(String kbId) {
        if (isEnabled()) {
            embeddingStoreFactory.evictStore(kbId);
        }
    }

    // ========================================================
    // Private Helpers
    // ========================================================

    private void ensureEnabled() {
        if (!isEnabled()) {
            throw new BizException(400, "RAG 模块未启用，请在 application.yml 中配置 agent.rag.enabled=true");
        }
    }

    private KnowledgeBase requireKnowledgeBase(String kbId) {
        KnowledgeBase kb = knowledgeService.getBaseById(kbId);
        if (kb == null) {
            throw new BizException(404, "知识库不存在: " + kbId);
        }
        return kb;
    }

    /**
     * 仅解析知识库绑定模型的向量维度，不涉及密钥解密；用于删除向量等无需调用 Embedding 接口的场景。
     */
    private int resolveDimensions(String kbId) {
        String modelId = requireKnowledgeBase(kbId).getEmbeddingModelId();
        ModelInfo model = modelConfigService.getModel(modelId);
        if (model == null || model.getDimensions() == null) {
            throw new BizException(400, "知识库绑定的向量模型不存在: kbId=" + kbId + ", modelId=" + modelId);
        }
        return model.getDimensions();
    }

    private static EmbeddingModelSpec toSpec(ModelConnection conn) {
        return EmbeddingModelSpec.builder()
                .modelId(conn.getModelId())
                .protocol(conn.getProtocol())
                .baseUrl(conn.getBaseUrl())
                .apiKey(conn.getApiKey())
                .modelName(conn.getModelName())
                .dimensions(conn.getDimensions())
                .sendDimensions(conn.isSendDimensions())
                .version(conn.getVersion())
                .build();
    }

    /**
     * IN_MEMORY 专用：首次访问某知识库时，从 MySQL 切片表重建内存向量。
     * <p>以内存库是否为空作为判据，加锁避免并发重复预热。ES / Milvus 不走此路径。</p>
     */
    private synchronized void warmUpInMemoryStore(String kbId, SimpleKnowledge knowledge, InMemoryStore store) {
        if (!store.isEmpty()) {
            return;
        }
        List<KnowledgeChunk> dbChunks = knowledgeService.listChunksByKbId(kbId);
        if (dbChunks.isEmpty()) {
            return;
        }
        log.info("[RAG-Warmup] 内存向量库为空，从 MySQL 回灌切片: kbId={}, 切片数={}", kbId, dbChunks.size());
        List<Document> docs = dbChunks.stream().map(c -> {
            DocumentMetadata meta = DocumentMetadata.builder()
                    .docId(c.getDocId())
                    .chunkId(String.valueOf(c.getChunkIndex()))
                    .content(TextBlock.builder().text(c.getContent()).build())
                    .build();
            return new Document(meta);
        }).collect(Collectors.toList());
        knowledge.addDocuments(docs).block();
        log.info("[RAG-Warmup] 内存向量库预热完成: kbId={}, 装载切片数={}", kbId, docs.size());
    }
}
