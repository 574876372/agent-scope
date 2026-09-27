package com.cl.agent.biz.event;

import com.cl.agent.biz.rag.EmbeddingTextBuilder;
import com.cl.agent.biz.rag.KbConfigResolver;
import com.cl.agent.biz.rag.KnowledgeFileStorage;
import com.cl.agent.biz.rag.RagKnowledgeProvider;
import com.cl.agent.commons.UserContext;
import com.cl.agent.enums.DocumentStatusEnum;
import com.cl.agent.model.KnowledgeBase;
import com.cl.agent.model.KnowledgeChunk;
import com.cl.agent.model.KnowledgeDocument;
import com.cl.agent.rag.chunk.ChunkOptions;
import com.cl.agent.rag.chunk.ChunkPiece;
import com.cl.agent.rag.chunk.DocBlock;
import com.cl.agent.rag.chunk.DocumentStructureReader;
import com.cl.agent.rag.chunk.StructuredChunker;
import com.cl.agent.rag.core.EmbeddingModelSpec;
import com.cl.agent.service.IKnowledgeService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.VDBStoreBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档异步解析与向量化任务事件监听器（上传入库与重新解析共用）。
 * <p>使用说明：监听 {@link DocumentIndexEvent}，由 {@code ragExecutor} 线程池异步执行。流水线：</p>
 * <ol>
 *   <li>清理该文档旧的向量与切片（新上传的文档没有旧数据，重新解析时据此覆盖）；</li>
 *   <li>{@link DocumentStructureReader} 还原标题 / 段落 / 表格结构，{@link StructuredChunker} 按知识库类型切片，
 *       每片记录章节路径与切片类型；</li>
 *   <li>以「文档名 › 章节路径 + 正文」批量计算向量，直接写入向量库；</li>
 *   <li>切片明细写入 MySQL，文档状态流转为 indexed；任一步失败流转为 failed 并记录原因。</li>
 * </ol>
 * <p>UserContext 恢复策略：事件携带发布时的 {@code userId}，任务开始前恢复、{@code finally} 中清理，防止线程复用污染。</p>
 */
@Slf4j
@Component
public class DocumentIndexEventListener {

    /** 失败时异常无 message 的兜底提示 */
    private static final String DEFAULT_ERROR_MESSAGE = "异步分片向量化失败";

    /** 每次写入向量库的文档数，避免超大文档一次性 bulk 请求过大 */
    private static final int STORE_WRITE_BATCH = 200;

    @Autowired
    private IKnowledgeService knowledgeService;

    /** 文档结构还原器；RAG 未启用时为 null */
    @Autowired(required = false)
    private DocumentStructureReader structureReader;

    /** 结构化切片器；RAG 未启用时为 null */
    @Autowired(required = false)
    private StructuredChunker structuredChunker;

    @Autowired
    private RagKnowledgeProvider ragKnowledgeProvider;

    /** 知识库切片参数解析（知识库级 → 类型预设 → 全局默认） */
    @Autowired
    private KbConfigResolver kbConfigResolver;

    /** 上传文档的本地文件存储，把落库的相对路径解析为实际文件位置 */
    @Autowired
    private KnowledgeFileStorage fileStorage;

    /**
     * 异步处理文档向量化流水线。
     * <p>使用说明：由 Spring 事件总线触发，运行在 {@code ragExecutor} 线程池中；
     * 文档状态 {@code parsing} → {@code indexed} 或 {@code failed}，失败原因落库供前端展示。</p>
     *
     * @param event {@link DocumentIndexEvent}，携带 {@code fileId} 与 {@code userId}，非空
     * @return 无返回值；结果体现在文档状态与切片表中
     */
    @Async("ragExecutor")
    @EventListener
    public void onDocumentIndexEvent(DocumentIndexEvent event) {
        try {
            UserContext.setUserId(event.getUserId());
            doParseAndIndex(event.getFileId());
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 文档清理、解析、切片、向量化与状态更新的核心流水线。
     * <p>内部捕获所有异常并落库，不向上层抛出，确保异步任务始终有明确终态。</p>
     *
     * @param docId 待处理的文档 ID，非空
     */
    private void doParseAndIndex(String docId) {
        long start = System.currentTimeMillis();
        KnowledgeDocument doc = knowledgeService.getDocumentById(docId);
        if (doc == null) {
            log.error("[Async-Pipeline] 未查询到对应文档元数据，跳过处理: docId={}", docId);
            return;
        }
        log.info("[Async-Pipeline] 文档入库流水线启动: docId={}, name={}", docId, doc.getName());

        try {
            // 1. 状态流转至 parsing（解析分片中），前端轮询可见
            updateStatus(doc, DocumentStatusEnum.PARSING);
            KnowledgeBase kb = knowledgeService.getBaseById(doc.getKbId());
            if (kb == null) {
                throw new IllegalStateException("所属知识库不存在: " + doc.getKbId());
            }

            // 2. 清理旧数据（重新解析时）：先删向量（需按切片 ID 定位），再物理删除切片记录（切片主键由内容派生，逻辑删除后重建会主键冲突）。
            //    新上传的文档没有旧切片，跳过向量清理——IN_MEMORY 模式下清理会丢弃整个知识库的内存向量并触发全量重建
            if (!knowledgeService.listChunksByDocId(docId).isEmpty()) {
                ragKnowledgeProvider.removeDocumentVectors(doc.getKbId(), docId);
            }
            knowledgeService.purgeChunksByDocId(docId);

            // 3. 结构化切片
            ChunkOptions options = kbConfigResolver.chunkOptions(kb);
            List<ChunkPiece> pieces = parseChunks(doc, options);

            // 4. 批量向量化并写入向量库
            List<Document> chunkDocs = writeVectors(doc, pieces);

            // 5. 切片明细写入 MySQL
            List<KnowledgeChunk> chunks = saveChunks(doc, pieces, chunkDocs);

            // 6. 终态 indexed
            markIndexed(doc, chunks);
            log.info("[Async-Pipeline] 文档入库完成: docId={}, 策略={}, 切片数={}, costMs={}",
                    docId, options.getStrategy(), chunks.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[Async-Pipeline] 文档入库失败: docId={}", docId, e);
            markFailed(doc, e);
        }
    }

    /**
     * 解析文件结构并按知识库配置切片。
     *
     * @param doc     文档元数据，需包含 filePath 与 type
     * @param options 切片参数
     * @return 切片列表，非空
     * @throws Exception 文件解析失败或无有效内容时抛出
     */
    private List<ChunkPiece> parseChunks(KnowledgeDocument doc, ChunkOptions options) throws Exception {
        if (structureReader == null || structuredChunker == null) {
            throw new IllegalStateException("RAG 模块未启用");
        }
        Path file = fileStorage.resolve(doc.getFilePath());
        List<DocBlock> blocks = structureReader.read(file, doc.getType());
        List<ChunkPiece> pieces = structuredChunker.chunk(blocks, options);
        if (pieces.isEmpty()) {
            throw new IllegalStateException("文件内容为空，无有效切片产生");
        }
        log.info("[Async-Pipeline] 结构化切片完成: docId={}, 结构块={}, 切片数={}, 表格切片={}, 带章节路径={}",
                doc.getId(), blocks.size(), pieces.size(),
                pieces.stream().filter(p -> ChunkPiece.TYPE_TABLE.equals(p.getChunkType())).count(),
                pieces.stream().filter(p -> !p.getSectionPath().isEmpty()).count());
        return pieces;
    }

    /**
     * 以「文档名 › 章节路径 + 正文」批量计算向量，构造向量文档并写入该知识库的向量存储。
     * <p>向量文档的正文仍为切片原文，docId / chunkId 为业务文档 ID 与切片序号，检索命中后据此回查切片表。</p>
     *
     * @return 已写入的向量文档，与 pieces 一一对应
     */
    private List<Document> writeVectors(KnowledgeDocument doc, List<ChunkPiece> pieces) {
        EmbeddingModelSpec spec = ragKnowledgeProvider.resolveEmbeddingSpec(doc.getKbId());
        List<String> texts = pieces.stream()
                .map(p -> EmbeddingTextBuilder.build(doc.getName(), p.getSectionPath(), p.getContent()))
                .collect(Collectors.toList());
        List<double[]> vectors = ragKnowledgeProvider.embedAll(spec, texts);

        List<Document> docs = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            ChunkPiece piece = pieces.get(i);
            DocumentMetadata meta = DocumentMetadata.builder()
                    .docId(doc.getId())
                    .chunkId(String.valueOf(i))
                    .content(TextBlock.builder().text(piece.getContent()).build())
                    .addPayload("docId", doc.getId())
                    .addPayload("chunkId", String.valueOf(i))
                    .addPayload("sectionPath", piece.getSectionPath())
                    .addPayload("chunkType", piece.getChunkType())
                    .build();
            Document d = new Document(meta);
            d.setEmbedding(vectors.get(i));
            docs.add(d);
        }
        VDBStoreBase store = ragKnowledgeProvider.getKnowledge(doc.getKbId()).getEmbeddingStore();
        for (int from = 0; from < docs.size(); from += STORE_WRITE_BATCH) {
            store.add(docs.subList(from, Math.min(from + STORE_WRITE_BATCH, docs.size()))).block();
        }
        log.info("[Async-Pipeline] 向量写入完成: docId={}, 条数={}", doc.getId(), docs.size());
        return docs;
    }

    /**
     * 批量持久化切片明细到 MySQL；切片主键复用向量文档 id，删除文档时据此清理远端向量。
     *
     * @return 已落库的切片实体列表
     */
    private List<KnowledgeChunk> saveChunks(KnowledgeDocument doc, List<ChunkPiece> pieces, List<Document> chunkDocs) {
        List<KnowledgeChunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            ChunkPiece piece = pieces.get(i);
            KnowledgeChunk chunk = KnowledgeChunk.builder()
                    .id(chunkDocs.get(i).getId())
                    .docId(doc.getId())
                    .kbId(doc.getKbId())
                    .content(piece.getContent())
                    .chunkIndex(i)
                    // 粗略估算：约 2 个字符折合 1 个 token，非精确值
                    .tokenCount(piece.getContent().length() / 2)
                    .sectionPath(truncate(piece.getSectionPath(), 512))
                    .chunkType(piece.getChunkType())
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

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
