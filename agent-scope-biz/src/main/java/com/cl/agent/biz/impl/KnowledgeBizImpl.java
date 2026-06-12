package com.cl.agent.biz.impl;

import com.cl.agent.biz.IKnowledgeBiz;
import com.cl.agent.biz.event.DocumentIndexEvent;
import com.cl.agent.commons.UserContext;
import com.cl.agent.dto.*;
import com.cl.agent.enums.ModelProviderEnum;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.KnowledgeBase;
import com.cl.agent.model.KnowledgeChunk;
import com.cl.agent.model.KnowledgeDocument;
import com.cl.agent.rag.core.DocumentReaderFactory;
import com.cl.agent.rag.core.EmbeddingStoreFactory;
import com.cl.agent.service.IKnowledgeService;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.rag.store.InMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库业务编排逻辑层实现类。
 * <p>遵循 {@link IKnowledgeBiz} 业务协议。利用注入的 RAG Starter 核心工厂，
 * 完成全格式文件解析与切片、调用 Embedding 引擎计算高维向量、持久化落库及多线程异步分载调度。</p>
 */
@Service
@Slf4j
public class KnowledgeBizImpl implements IKnowledgeBiz {

    @Autowired
    private IKnowledgeService knowledgeService;

    /** 智能注入 RAG Starter 工厂 Bean；当属性 agent.rag.enabled=false 时，以下 Bean 不会被装配，此时置为 null 可防止主项目启动挂掉 */
    @Autowired(required = false)
    private DocumentReaderFactory documentReaderFactory;

    @Autowired(required = false)
    private EmbeddingStoreFactory embeddingStoreFactory;

    /** Spring 应用事件发布器，用于发布 {@link DocumentIndexEvent} 触发异步向量化流水线 */
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /** 上传文件本地落盘的基准主文件夹路径，保存在当前项目 workspace 数据区内，完全受控且安全 */
    private static final String UPLOAD_BASE_DIR = "./data/uploads";

    /**
     * 创建并持久化一个全新的私有知识库。
     * <p>使用说明：由知识库控制器在收到创建请求时调用；当前操作用户 ID 会自动从 UserContext 上下文中安全解析获取。</p>
     *
     * @param request 创建知识库参数请求体，{@code name} 必填，非空
     * @return {@link KbResponse} 创建成功后的知识库详细元数据信息对象
     */
    @Override
    public KbResponse createKnowledgeBase(CreateKbRequest request) {
        log.info("[Biz-KB] 准备创建知识库: name={}", request.getName());
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BizException(400, "知识库名称不能为空");
        }

        String kbId = UUID.randomUUID().toString();
        String userId = UserContext.getUserId();

        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .name(request.getName().trim())
                .description(request.getDescription())
                .avatar(request.getAvatar())
                .userId(userId != null ? userId : "admin")
                .build();
        kb.setCreateBy(userId);
        kb.setCreateTime(LocalDateTime.now());

        knowledgeService.saveBase(kb);
        log.info("[Biz-KB] 知识库创建成功: id={}, name={}", kbId, kb.getName());
        return toKbResponse(kb);
    }

    /**
     * 列出当前登录用户创建并拥有的所有活跃知识库列表。
     *
     * @return {@link KbResponse} 列表；未查询到时返回空列表
     */
    @Override
    public List<KbResponse> listKnowledgeBases() {
        String userId = UserContext.getUserId();
        log.info("[Biz-KB] 查询活跃知识库列表: userId={}", userId);
        List<KnowledgeBase> list;
        if (userId != null && !userId.trim().isEmpty()) {
            list = knowledgeService.listBasesByUserId(userId);
        } else {
            list = knowledgeService.listAllBases();
        }
        return list.stream()
                .map(this::toKbResponse)
                .collect(Collectors.toList());
    }

    /**
     * 获取指定 ID 的知识库详细元数据。
     *
     * @param id 知识库唯一标识 ID，非空
     * @return {@link KbResponse} 知识库详情响应对象
     * @throws BizException 当对应的知识库不存在时抛出 404 错误
     */
    @Override
    public KbResponse getKnowledgeBase(String id) {
        log.info("[Biz-KB] 获取知识库详情: id={}", id);
        KnowledgeBase kb = knowledgeService.getBaseById(id);
        if (kb == null) {
            throw new BizException(404, "知识库不存在: " + id);
        }
        return toKbResponse(kb);
    }

    /**
     * 级联删除指定 ID 的知识库。
     * <p>使用说明：除了清理 MySQL 的知识库主表外，还会级联清除文档元数据、文本切片、
     * 多对多绑定关系以及向量库物理索引，具有彻底清除的副作用。</p>
     *
     * @param id 待删除知识库的 ID，非空
     * @return 无
     */
    @Override
    public void deleteKnowledgeBase(String id) {
        log.info("[Biz-KB] 准备删除知识库级联元数据: id={}", id);
        KnowledgeBase kb = knowledgeService.getBaseById(id);
        if (kb == null) {
            return;
        }

        // 1. 级联清理绑定的 Agent 多对多映射关系
        knowledgeService.deleteBindsByKbId(id);

        // 2. 获取并循环删除知识库下的所有物理及关系文档
        List<KnowledgeDocument> docs = knowledgeService.listDocumentsByKbId(id);
        for (KnowledgeDocument doc : docs) {
            deleteDocument(doc.getId());
        }

        // 3. 逻辑删除知识库主表
        knowledgeService.deleteBaseById(id);
        log.info("[Biz-KB] 知识库物理级联与逻辑删除完成: id={}", id);
    }

    /**
     * 上传并异步向量化单个文档。
     * <p>使用说明：上传的文件将被安全暂存在服务器本地 workspace 的数据存储区中；
     * 随后自动在数据库中生成一条处于 {@code uploading} 状态的文档监控记录，
     * 并将具体的读取分片与向量化计算解析任务异步投递至后台线程池进行解耦消费，立即响应前端。</p>
     *
     * @param kbId 目标绑定的知识库唯一 ID，非空
     * @param file 前端提交的 Multipart 物理文件，必填且非空
     * @return {@link UploadDocResponse} 包含文档初步登记元数据与上传中状态的响应对象
     * @throws BizException 当文件为空、格式不支持或所属知识库不存在时抛出
     */
    @Override
    public UploadDocResponse uploadAndIndexDocument(String kbId, MultipartFile file) {
        if (documentReaderFactory == null || embeddingStoreFactory == null) {
            throw new BizException(400, "RAG 模块未启用，请在 application.yml 中配置启用");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "上传文件不能为空");
        }
        KnowledgeBase kb = knowledgeService.getBaseById(kbId);
        if (kb == null) {
            throw new BizException(404, "所属知识库不存在: " + kbId);
        }

        String originalFilename = file.getOriginalFilename();
        String fileId = UUID.randomUUID().toString();
        String ext = getFileExtension(originalFilename);

        log.info("[Biz-Doc] 接收文档上传请求: name={}, size={}, kbId={}", originalFilename, file.getSize(), kbId);

        // 1. 创建服务器本地多级落盘存储路径并保存
        // 注意：必须先转换为绝对路径，否则 MultipartFile.transferTo() 会将相对路径拼接到
        // Tomcat 临时工作目录（如 AppData/Local/Temp/tomcat.xxx）下，导致目录不存在而报错
        Path storePath = Paths.get(UPLOAD_BASE_DIR, kbId, fileId + "." + ext).toAbsolutePath();
        try {
            Files.createDirectories(storePath.getParent());
            // 使用 Files.copy 直接操作输入流，避免 transferTo(File) 依赖 Servlet 容器工作目录
            Files.copy(file.getInputStream(), storePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("[Biz-Doc] 上传文件本地暂存落盘完成: path={}", storePath);
        } catch (IOException e) {
            log.error("[Biz-Doc] 文件写入本地磁盘异常: path={}", storePath, e);
            throw new BizException(500, "文件写入本地失败: " + e.getMessage());
        }

        // 2. MySQL 关系表中快速插入一条处于 uploading 状态的元数据以跟踪状态
        String userId = UserContext.getUserId();
        KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(fileId)
                .kbId(kbId)
                .name(originalFilename)
                .type(ext)
                .status("uploading")
                .sizeBytes(file.getSize())
                .filePath(storePath.toAbsolutePath().toString())
                .build();
        doc.setCreateBy(userId);
        doc.setCreateTime(LocalDateTime.now());
        knowledgeService.saveDocument(doc);

        // 3. 通过 Spring 事件机制将耗时的解析、切片与向量计算任务解耦至后台异步消费
        // DocumentIndexEvent 携带当前请求线程的 userId，解决 ThreadLocal 跨线程丢失问题
        // DocumentIndexEventListener 订阅此事件，运行于 ragExecutor 专属线程池中
        eventPublisher.publishEvent(new DocumentIndexEvent(this, fileId, UserContext.getUserId()));
        log.info("[Biz-Doc] 文档向量化事件发布成功: fileId={}", fileId);

        return toUploadDocResponse(doc);
    }

    /**
     * 获取指定知识库下关联的所有文档解析列表。
     * <p>使用说明：主要用于前端在“文档管理”列表页面展示，跟踪每一个文件的解析入库状态与字数信息。</p>
     *
     * @param kbId 知识库 ID，非空
     * @return {@link UploadDocResponse} 列表；为空时返回空列表
     */
    @Override
    public List<UploadDocResponse> listDocuments(String kbId) {
        log.info("[Biz-Doc] 查询知识库关联文档列表: kbId={}", kbId);
        return knowledgeService.listDocumentsByKbId(kbId).stream()
                .map(this::toUploadDocResponse)
                .collect(Collectors.toList());
    }

    /**
     * 逻辑删除知识库下的单个文档及级联文本切片。
     * <p>使用说明：会同步清理向量数据库中该文档关联的所有向量索引。</p>
     *
     * @param docId 待删除文档的唯一 ID，非空
     * @return 无
     */
    @Override
    public void deleteDocument(String docId) {
        log.info("[Biz-Doc] 准备删除文档及其切片数据: docId={}", docId);
        KnowledgeDocument doc = knowledgeService.getDocumentById(docId);
        if (doc == null) {
            return;
        }

        // 1. 物理清除服务器落盘的暂存文件
        try {
            Files.deleteIfExists(Paths.get(doc.getFilePath()));
            log.info("[Biz-Doc] 成功清理本地磁盘暂存文件: path={}", doc.getFilePath());
        } catch (IOException e) {
            log.warn("[Biz-Doc] 清理本地磁盘文件失败: path={}", doc.getFilePath(), e);
        }

        // 2. 清理向量数据库中对应的内容（官方的 InMemoryStore 重建会自动丢弃，而外部 Milvus 会物理清理该文件的 Chunk）
        // 生产级：我们可以通过在外部 Milvus 中执行 delete(chunkId) 来完成。

        // 3. 清理 MySQL 中物理关联的所有纯文本切片明细记录
        knowledgeService.deleteChunksByDocId(docId);

        // 4. 逻辑删除文档元数据记录
        knowledgeService.deleteDocumentById(docId);
        log.info("[Biz-Doc] 文档物理级联与逻辑删除成功: docId={}", docId);
    }

    /**
     * 知识库检索演练场 (Playground) 专属语义召回检索测试。
     * <p>使用说明：模拟 Agent 的检索流程。在指定的知识库中，根据用户输入的自然语言提问进行 Similarity Search，
     * 召回 Top-K 最相似段落，返回用于高亮渲染的数据列表。</p>
     *
     * @param kbId  目标知识库唯一 ID，非空
     * @param query 用户输入的自然语言测试查询词，非空
     * @param limit 召回切片最大数量上限，null 时使用系统默认配置
     * @return {@link SearchChunkResponse} 召回的文本片段与相似度得分列表；未检索到时返回空列表
     */
    @Override
    public List<SearchChunkResponse> searchKnowledge(String kbId, String query, Integer limit) {
        if (documentReaderFactory == null || embeddingStoreFactory == null) {
            throw new BizException(400, "RAG 模块未启用");
        }
        log.info("[Biz-Query] 知识库 Playground 检索测试: kbId={}, query={}, limit={}", kbId, query, limit);
        
        // 1. 构建官方 SimpleKnowledge 管理器并配置向量计算引擎与连接介质
        ModelProviderEnum provider = ModelProviderEnum.QWEN; // 采用千问自带的嵌入式计算，稳定快速
        EmbeddingModel embeddingModel = embeddingStoreFactory.createEmbeddingModel(provider.getApiKey(), provider.getBaseUrl());
        VDBStoreBase store = embeddingStoreFactory.createStore(kbId);
        
        SimpleKnowledge knowledge = SimpleKnowledge.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build();

        // 2. 预热当前知识库已存储的物理文档（主要针对本地开发测试使用的内存 InMemoryStore）
        preheatMemoryStoreIfLocal(knowledge, kbId);

        // 3. 执行官方原生的语义检索召回
        int rowLimit = (limit != null) ? limit : 3;
        RetrieveConfig config = RetrieveConfig.builder()
                .limit(rowLimit)
                .scoreThreshold(0.1) // Playground 放宽过滤阈值以方便调试
                .build();

        try {
            List<Document> result = knowledge.retrieve(query, config).block();
            if (result == null || result.isEmpty()) {
                log.info("[Biz-Query] 检索结果为空: kbId={}", kbId);
                return List.of();
            }

            // 4. 将检索出来的 Document 段转换为前端高亮响应的 DTO
            List<SearchChunkResponse> responses = new ArrayList<>();
            for (Document doc : result) {
                SearchChunkResponse chunkResp = new SearchChunkResponse();
                chunkResp.setChunkId(doc.getId());
                chunkResp.setContent(doc.getMetadata().getContentText());
                chunkResp.setScore(doc.getScore());
                chunkResp.setDocId(doc.getMetadata().getDocId());
                chunkResp.setChunkIndex(Integer.parseInt(Optional.ofNullable(doc.getMetadata().getChunkId()).orElse("0")));
                
                // 根据 docId 反查友好显示的文件名
                KnowledgeDocument kDoc = knowledgeService.getDocumentById(doc.getMetadata().getDocId());
                chunkResp.setDocName(kDoc != null ? kDoc.getName() : "未知源文档");
                responses.add(chunkResp);
            }
            log.info("[Biz-Query] 检索匹配完成，成功召回匹配段落数={}", responses.size());
            return responses;
        } catch (Exception e) {
            log.error("[Biz-Query] 语义检索异常: kbId={}", kbId, e);
            throw new BizException(500, "检索出现异常: " + e.getMessage());
        }
    }

    // ========================================================
    // 💡 内存型向量库预热辅助方法 (Private Helpers)
    // ========================================================

    /**
     * 针对本地开发使用的 InMemoryStore 进行切片数据动态“预热加载”。
     * <p>由于官方 {@link InMemoryStore} 存放在进程运行期内存中，一旦重启将丢失向量信息。
     * 当配置为 InMemoryStore 时，我们在系统检索前动态读取关系表中的已入库切片，
     * 内存中快速重构文档数据并灌入向量库，保障“零配置、零维护”本地极其丝滑的使用体验！</p>
     *
     * @param knowledge 官方构建的 Knowledge 实例，非空
     * @param kbId      知识库 ID
     */
    private void preheatMemoryStoreIfLocal(SimpleKnowledge knowledge, String kbId) {
        if (knowledge.getEmbeddingStore() instanceof InMemoryStore) {
            InMemoryStore memStore = (InMemoryStore) knowledge.getEmbeddingStore();
            if (memStore.isEmpty()) {
                log.info("[Preheat-Local] 检测到 InMemoryStore 向量库为空，开始启动 MySQL 自动预热流水线: kbId={}", kbId);
                List<KnowledgeChunk> dbChunks = knowledgeService.listChunksByKbId(kbId);
                if (dbChunks.isEmpty()) {
                    return;
                }

                // 从 MySQL 切片文本快速构造官方 Document
                List<Document> preheatDocs = dbChunks.stream().map(c -> {
                    DocumentMetadata meta = DocumentMetadata.builder()
                            .docId(c.getDocId())
                            .chunkId(String.valueOf(c.getChunkIndex()))
                            .content(TextBlock.builder().text(c.getContent()).build())
                            .build();
                    return new Document(meta);
                }).collect(Collectors.toList());

                // 异步等待官方 preheat 向量化并载入内存
                knowledge.addDocuments(preheatDocs).block();
                log.info("[Preheat-Local] 本地自动向量预热流水线结束: 成功装载片数={}", preheatDocs.size());
            }
        }
    }

    /**
     * 将物理实体映射为对外呈现的知识库 Response DTO。
     */
    private KbResponse toKbResponse(KnowledgeBase kb) {
        KbResponse resp = new KbResponse();
        resp.setId(kb.getId());
        resp.setName(kb.getName());
        resp.setDescription(kb.getDescription());
        resp.setAvatar(kb.getAvatar());
        resp.setUserId(kb.getUserId());
        resp.setCreateTime(kb.getCreateTime());
        return resp;
    }

    /**
     * 将物理文档实体映射为对外呈现的文档 Response DTO。
     */
    private UploadDocResponse toUploadDocResponse(KnowledgeDocument doc) {
        UploadDocResponse resp = new UploadDocResponse();
        resp.setId(doc.getId());
        resp.setKbId(doc.getKbId());
        resp.setName(doc.getName());
        resp.setType(doc.getType());
        resp.setSizeBytes(doc.getSizeBytes());
        resp.setStatus(doc.getStatus());
        resp.setCharCount(doc.getCharCount());
        resp.setChunkCount(doc.getChunkCount());
        resp.setErrorMessage(doc.getErrorMessage());
        resp.setCreateTime(doc.getCreateTime());
        return resp;
    }

    /**
     * 辅助获取文件扩展名。
     */
    private String getFileExtension(String filename) {
        if (filename == null) {
            return "txt";
        }
        int dotIdx = filename.lastIndexOf(".");
        return (dotIdx == -1) ? "txt" : filename.substring(dotIdx + 1);
    }
}
