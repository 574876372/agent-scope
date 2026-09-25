package com.cl.agent.biz.event;

import com.cl.agent.biz.rag.KnowledgeFileStorage;
import com.cl.agent.biz.rag.RagKnowledgeProvider;
import com.cl.agent.commons.UserContext;
import com.cl.agent.enums.DocumentStatusEnum;
import com.cl.agent.model.KnowledgeChunk;
import com.cl.agent.model.KnowledgeDocument;
import com.cl.agent.rag.core.DocumentReaderFactory;
import com.cl.agent.service.IKnowledgeService;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
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

    /** 失败时异常无 message 的兜底提示 */
    private static final String DEFAULT_ERROR_MESSAGE = "异步分片向量化失败";

    @Autowired
    private IKnowledgeService knowledgeService;

    /** 智能注入 RAG Starter 工厂 Bean；RAG 未启用时为 null */
    @Autowired(required = false)
    private DocumentReaderFactory documentReaderFactory;

    @Autowired
    private RagKnowledgeProvider ragKnowledgeProvider;

    /** 上传文档的本地文件存储，把落库的相对路径解析为实际文件位置 */
    @Autowired
    private KnowledgeFileStorage fileStorage;

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
            // 1. 状态流转至 parsing（解析分片中），前端轮询可见
            updateStatus(doc, DocumentStatusEnum.PARSING);

            // 2. 解析文件并切片，注入业务 docId / chunkId
            List<Document> chunkDocs = parseChunks(doc);

            // 3. 计算 Embedding 并写入向量库（先写向量，再写 MySQL，切片主键复用向量文档 id）
            writeVectors(doc.getKbId(), chunkDocs);

            // 4. 切片明细批量落库 MySQL，供 IN_MEMORY 预热回灌与按文档删除向量使用
            List<KnowledgeChunk> chunks = saveChunks(doc, chunkDocs);

            // 5. 状态流转至终态 indexed，回写统计信息
            markIndexed(doc, chunks);
            log.info("[Async-Pipeline] 文档向量化流水线执行成功: docId={}, 切片总数={}", docId, chunks.size());
        } catch (Exception e) {
            // 任一步骤失败均流转至终态 failed，异常不向上抛出，保证异步任务有明确终态
            log.error("[Async-Pipeline] 文档向量化出现严重异常: docId={}", docId, e);
            markFailed(doc, e);
        }
    }

    /**
     * 解析物理文件并切片，再为每个切片注入业务 docId 与序号 chunkId。
     *
     * @param doc 文档元数据，需包含 filePath 与 type
     * @return 可直接写入向量库的切片列表，非空
     * @throws RuntimeException 文件无有效内容时抛出
     */
    private List<Document> parseChunks(KnowledgeDocument doc) {
        // 按文件后缀路由至 AgentScope 官方 Reader，完成文本抽取与段落切片
        String filePath = fileStorage.resolve(doc.getFilePath()).toString();
        List<Document> parsedDocs = documentReaderFactory.parseFile(filePath, doc.getType());
        if (parsedDocs.isEmpty()) {
            throw new RuntimeException("文件内容为空，无有效切片产生");
        }

        // 切片序号即 chunkId，与 MySQL 中的 chunkIndex 保持一致
        List<Document> chunkDocs = new ArrayList<>(parsedDocs.size());
        for (int i = 0; i < parsedDocs.size(); i++) {
            chunkDocs.add(rebuildChunkDocument(parsedDocs.get(i), doc.getId(), i));
        }
        return chunkDocs;
    }

    /**
     * 以可变 payload 重建切片 Document，保留原切片内容与向量相关字段。
     * <p>Reader 产出的 docId/chunkId 与业务无关，替换为真实文档 ID 与切片序号，
     * 以便按文档删除向量并与 MySQL 切片表对应。</p>
     */
    private Document rebuildChunkDocument(Document parsedDoc, String docId, int index) {
        String chunkId = String.valueOf(index);

        // payload 随向量一起写入向量库，检索命中时可据此反查所属文档
        Map<String, Object> payload = new HashMap<>();
        payload.put("docId", docId);
        payload.put("chunkId", chunkId);

        // 仅复用原切片的文本内容，docId / chunkId 替换为业务值
        DocumentMetadata meta = DocumentMetadata.builder()
                .docId(docId)
                .chunkId(chunkId)
                .content(parsedDoc.getMetadata().getContent())
                .payload(payload)
                .build();

        // 原切片若已携带向量等字段则一并保留；通常为空，由 addDocuments 统一计算
        Document newDoc = new Document(meta);
        if (parsedDoc.getEmbedding() != null) {
            newDoc.setEmbedding(parsedDoc.getEmbedding());
        }
        if (parsedDoc.getScore() != null) {
            newDoc.setScore(parsedDoc.getScore());
        }
        if (parsedDoc.getVectorName() != null) {
            newDoc.setVectorName(parsedDoc.getVectorName());
        }
        return newDoc;
    }

    /**
     * 计算 Embedding 并写入该知识库的向量存储（官方 addDocuments 内部完成向量化）。
     */
    private void writeVectors(String kbId, List<Document> chunkDocs) {
        // Embedding 模型 + 该知识库的向量存储实例（与检索端共享同一实例）
        SimpleKnowledge knowledge = ragKnowledgeProvider.getKnowledge(kbId);
        // addDocuments 为响应式接口，异步线程内 block() 同步等待写入完成
        knowledge.addDocuments(chunkDocs).block();
        log.info("[Async-Pipeline] SimpleKnowledge 向量化写入成功: size={}", chunkDocs.size());
    }

    /**
     * 批量持久化切片明细到 MySQL。
     * <p>切片主键直接使用向量文档 id（Document.getId()），删除文档时据此逐条清理远端向量。</p>
     *
     * @return 已落库的切片实体列表
     */
    private List<KnowledgeChunk> saveChunks(KnowledgeDocument doc, List<Document> chunkDocs) {
        List<KnowledgeChunk> chunks = new ArrayList<>(chunkDocs.size());
        for (int i = 0; i < chunkDocs.size(); i++) {
            Document chunkDoc = chunkDocs.get(i);
            String contentText = chunkDoc.getMetadata().getContentText();

            // id 复用向量文档 id，是 MySQL 与向量库之间的关联键
            KnowledgeChunk chunk = KnowledgeChunk.builder()
                    .id(chunkDoc.getId())
                    .docId(doc.getId())
                    .kbId(doc.getKbId())
                    .content(contentText)
                    .chunkIndex(i)
                    // 粗略估算：约 2 个字符折合 1 个 token，非精确值
                    .tokenCount(contentText.length() / 2)
                    .build();
            chunk.setCreateTime(LocalDateTime.now());
            chunks.add(chunk);
        }
        knowledgeService.saveChunksBatch(chunks);
        return chunks;
    }

    /**
     * 流转至终态 indexed，并回写字符数与切片数统计。
     */
    private void markIndexed(KnowledgeDocument doc, List<KnowledgeChunk> chunks) {
        int charCount = chunks.stream().mapToInt(c -> c.getContent().length()).sum();
        doc.setCharCount(charCount);
        doc.setChunkCount(chunks.size());
        // 成功时清空错误信息，避免残留旧的失败描述
        doc.setErrorMessage(null);
        updateStatus(doc, DocumentStatusEnum.INDEXED);
    }

    /**
     * 流转至终态 failed，落库具体原因供前端渲染提示。
     */
    private void markFailed(KnowledgeDocument doc, Exception e) {
        doc.setErrorMessage(e.getMessage() != null ? e.getMessage() : DEFAULT_ERROR_MESSAGE);
        updateStatus(doc, DocumentStatusEnum.FAILED);
    }

    /**
     * 设置文档状态并立即落库，所有状态流转统一经由此方法。
     */
    private void updateStatus(KnowledgeDocument doc, DocumentStatusEnum status) {
        doc.setStatus(status.getCode());
        knowledgeService.saveDocument(doc);
    }
}
