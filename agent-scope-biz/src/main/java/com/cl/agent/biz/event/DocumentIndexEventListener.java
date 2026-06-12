package com.cl.agent.biz.event;

import com.cl.agent.commons.UserContext;
import com.cl.agent.enums.ModelProviderEnum;
import com.cl.agent.model.KnowledgeChunk;
import com.cl.agent.model.KnowledgeDocument;
import com.cl.agent.rag.core.DocumentReaderFactory;
import com.cl.agent.rag.core.EmbeddingStoreFactory;
import com.cl.agent.service.IKnowledgeService;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.VDBStoreBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 文档异步解析与向量化任务事件监听器。
 * <p>使用说明：监听 {@link DocumentIndexEvent}，由 Spring 的 {@code ragExecutor} 线程池异步驱动执行，
 * 完成文件解析、切片向量化写入与 MySQL 状态流转等全流程。与发布方 {@code KnowledgeBizImpl}
 * 完全解耦——发布方仅投递事件即可立即返回响应，本监听器在后台独立消费。</p>
 * <p>UserContext 恢复策略：事件对象携带发布时的 {@code userId}，监听器在任务开始前调用
 * {@code UserContext.setUserId()} 恢复上下文，并在 {@code finally} 块中 {@code clear()}，
 * 防止线程池复用时污染后续任务。</p>
 */
@Slf4j
@Component
public class DocumentIndexEventListener {

    @Autowired
    private IKnowledgeService knowledgeService;

    /** 智能注入 RAG Starter 工厂 Bean；RAG 未启用时为 null */
    @Autowired(required = false)
    private DocumentReaderFactory documentReaderFactory;

    @Autowired(required = false)
    private EmbeddingStoreFactory embeddingStoreFactory;

    /**
     * 异步处理文档向量化流水线。
     * <p>使用说明：由 Spring 事件总线触发，运行在名为 {@code ragExecutor} 的专属线程池中；
     * 严格跟踪文档状态 {@code parsing} → {@code indexed} 或 {@code failed}，
     * 失败时将错误信息落库以便前端渲染提示。</p>
     *
     * @param event {@link DocumentIndexEvent}，携带 {@code fileId} 与 {@code userId}，非空
     */
    @Async("ragExecutor")
    @EventListener
    public void onDocumentIndexEvent(DocumentIndexEvent event) {
        final String fileId = event.getFileId();
        final String userId = event.getUserId();

        // 恢复发布方请求线程的用户上下文，保证 MyMetaObjectHandler 等组件正常工作
        try {
            UserContext.setUserId(userId);
            doParseAndIndex(fileId);
        } finally {
            // 防止线程池线程复用时 ThreadLocal 污染后续任务的上下文
            UserContext.clear();
        }
    }

    /**
     * 文档读取、分片、向量计算与状态更新的核心流水线。
     * <p>使用说明：被 {@link #onDocumentIndexEvent} 调用，内部通过 try-catch 捕获所有异常并落库，
     * 不向上层抛出，确保异步任务始终有明确的终态。</p>
     *
     * @param docId 待处理的文档 ID，非空
     */
    private void doParseAndIndex(String docId) {
        log.info("[Async-Pipeline] 文档向量化流水线启动: docId={}", docId);
        KnowledgeDocument doc = knowledgeService.getDocumentById(docId);
        if (doc == null) {
            log.error("[Async-Pipeline] 未查询到对应文档元数据，跳过处理: docId={}", docId);
            return;
        }

        try {
            // 1. 将状态流转至 parsing（解析分片中）
            doc.setStatus("parsing");
            knowledgeService.saveDocument(doc);

            // 2. 调用 Starter 工厂的多格式路由引擎解析文件并生成切片列表
            List<Document> parsedDocs = documentReaderFactory.parseFile(doc.getFilePath(), doc.getType());
            if (parsedDocs.isEmpty()) {
                throw new RuntimeException("文件内容为空，无有效切片产生");
            }

            // 3. 构建官方 SimpleKnowledge 并注入 Embedding API 与向量存储介质
            ModelProviderEnum provider = ModelProviderEnum.QWEN;
            EmbeddingModel embeddingModel = embeddingStoreFactory.createEmbeddingModel(
                    provider.getApiKey(), provider.getBaseUrl());
            VDBStoreBase store = embeddingStoreFactory.createStore(doc.getKbId());

            SimpleKnowledge knowledge = SimpleKnowledge.builder()
                    .embeddingModel(embeddingModel)
                    .embeddingStore(store)
                    .build();

            // 重构 Document，使用可变的 payload 并注入真实的 docId 与 chunkId
            List<Document> finalDocs = new ArrayList<>();
            for (int i = 0; i < parsedDocs.size(); i++) {
                Document parsedDoc = parsedDocs.get(i);
                
                Map<String, Object> payload = new HashMap<>();
                payload.put("docId", docId);
                payload.put("chunkId", String.valueOf(i));

                DocumentMetadata newMeta = DocumentMetadata.builder()
                                .docId(docId)
                                .chunkId(String.valueOf(i))
                                .content(parsedDoc.getMetadata().getContent())
                                .payload(payload)
                                .build();

                Document newDoc = new Document(newMeta);
                if (parsedDoc.getEmbedding() != null) {
                    newDoc.setEmbedding(parsedDoc.getEmbedding());
                }
                if (parsedDoc.getScore() != null) {
                    newDoc.setScore(parsedDoc.getScore());
                }
                if (parsedDoc.getVectorName() != null) {
                    newDoc.setVectorName(parsedDoc.getVectorName());
                }
                finalDocs.add(newDoc);
            }

            // 4. 官方 addDocuments 自动计算 Embedding 并写入向量存储器
            knowledge.addDocuments(finalDocs).block();
            log.info("[Async-Pipeline] SimpleKnowledge 向量化写入成功: size={}", finalDocs.size());

            // 5. 批量持久化切片明细到 MySQL
            List<KnowledgeChunk> chunks = new ArrayList<>();
            int charCount = 0;
            for (int i = 0; i < finalDocs.size(); i++) {
                Document parsedDoc = finalDocs.get(i);
                String contentText = parsedDoc.getMetadata().getContentText();
                charCount += contentText.length();

                KnowledgeChunk chunk = KnowledgeChunk.builder()
                        .id(UUID.randomUUID().toString())
                        .docId(docId)
                        .kbId(doc.getKbId())
                        .content(contentText)
                        .chunkIndex(i)
                        .tokenCount(contentText.length() / 2)
                        .build();
                chunk.setCreateTime(LocalDateTime.now());
                chunks.add(chunk);
            }
            knowledgeService.saveChunksBatch(chunks);

            // 6. 更新文档状态至最终态：indexed（已入库）
            doc.setStatus("indexed");
            doc.setCharCount(charCount);
            doc.setChunkCount(chunks.size());
            doc.setErrorMessage(null);
            knowledgeService.saveDocument(doc);
            log.info("[Async-Pipeline] 文档向量化流水线执行成功: docId={}, 切片总数={}", docId, chunks.size());

        } catch (Exception e) {
            log.error("[Async-Pipeline] 文档向量化出现严重异常: docId={}", docId, e);
            // 状态回流至失败态：failed，落库具体原因供前端渲染提示
            doc.setStatus("failed");
            doc.setErrorMessage(e.getMessage() != null ? e.getMessage() : "异步分片向量化失败");
            knowledgeService.saveDocument(doc);
        }
    }
}
