package com.cl.agent.service;

import com.cl.agent.model.AgentKbRel;
import com.cl.agent.model.KnowledgeBase;
import com.cl.agent.model.KnowledgeChunk;
import com.cl.agent.model.KnowledgeDocument;
import java.util.List;

/**
 * 知识库、文档、分片及 Agent 关系持久化数据服务层核心接口。
 * <p>封装底层 Mapper 数据库交互动作，为上层业务编排层 (Biz) 提供高内聚的原子持久化事务处理支持。</p>
 */
public interface IKnowledgeService {

    // ========================================================
    // 1. 知识库主表 (t_knowledge_base) CRUD
    // ========================================================

    /**
     * 保存或更新知识库基础元数据实体。
     * <p>使用说明：当 ID 已存在时执行更新，否则执行全新插入。</p>
     *
     * @param kb 待保存的知识库信息实体，非空
     * @return 无
     */
    void saveBase(KnowledgeBase kb);

    /**
     * 根据主键 ID 获取对应的知识库实体。
     *
     * @param id 知识库唯一标识 ID，非空
     * @return {@link KnowledgeBase} 知识库实体；不存在时返回 null
     */
    KnowledgeBase getBaseById(String id);

    /**
     * 获取指定创建用户下的所有活跃知识库列表。
     *
     * @param userId 创建拥有者用户 ID，非空
     * @return {@link KnowledgeBase} 列表；未查询到时返回空列表
     */
    List<KnowledgeBase> listBasesByUserId(String userId);

    /**
     * 获取系统内所有活跃的知识库列表。
     *
     * @return {@link KnowledgeBase} 列表；为空时返回空列表
     */
    List<KnowledgeBase> listAllBases();

    /**
     * 根据主键 ID 逻辑删除对应的知识库实体。
     *
     * @param id 待删除知识库的 ID，非空
     * @return 无
     */
    void deleteBaseById(String id);

    // ========================================================
    // 2. 知识库文档明细表 (t_knowledge_document) CRUD
    // ========================================================

    /**
     * 保存或更新上传的文档元数据明细记录。
     *
     * @param doc 待保存的文档元数据明细，非空
     * @return 无
     */
    void saveDocument(KnowledgeDocument doc);

    /**
     * 根据主键 ID 获取对应的文档明细元数据。
     *
     * @param id 文档唯一标识 ID，非空
     * @return {@link KnowledgeDocument} 文档实体；不存在时返回 null
     */
    KnowledgeDocument getDocumentById(String id);

    /**
     * 获取指定知识库下关联的所有文档明细列表（包含上传中/解析中/已入库等状态）。
     *
     * @param kbId 知识库 ID，非空
     * @return {@link KnowledgeDocument} 列表；未查询到时返回空列表
     */
    List<KnowledgeDocument> listDocumentsByKbId(String kbId);

    /**
     * 根据主键 ID 逻辑删除对应的文档元数据。
     *
     * @param id 待删除文档的 ID，非空
     * @return 无
     */
    void deleteDocumentById(String id);

    // ========================================================
    // 3. 知识库文档文本切片表 (t_knowledge_chunk) CRUD
    // ========================================================

    /**
     * 批量保存或插入切片数据。
     * <p>使用说明：常在文档异步切片分块向量化成功后被业务层批量落库审计。</p>
     *
     * @param chunks 待持久化的切片实体列表，非空
     * @return 无
     */
    void saveChunksBatch(List<KnowledgeChunk> chunks);

    /**
     * 获取指定上传文档下已存储的所有纯文本切片记录。
     *
     * @param docId 文档唯一 ID，非空
     * @return {@link KnowledgeChunk} 列表；未查询到时返回空列表
     */
    List<KnowledgeChunk> listChunksByDocId(String docId);

    /**
     * 获取指定知识库下关联的所有切片信息列表（用于大批审计或本地检索对比）。
     *
     * @param kbId 知识库唯一 ID，非空
     * @return {@link KnowledgeChunk} 列表；未查询到时返回空列表
     */
    List<KnowledgeChunk> listChunksByKbId(String kbId);

    /**
     * 级联删除指定文档下的所有文本切片实体。
     *
     * @param docId 文档唯一 ID，非空
     * @return 无
     */
    void deleteChunksByDocId(String docId);

    /**
     * 级联删除指定知识库下的所有文本切片实体。
     *
     * @param kbId 知识库唯一 ID，非空
     * @return 无
     */
    void deleteChunksByKbId(String kbId);

    // ========================================================
    // 4. Agent 与知识库多对多授权绑定表 (t_agent_kb_rel) CRUD
    // ========================================================

    /**
     * 替换指定 Agent 所绑定的关联知识库列表。
     * <p>使用说明：会首先清理该 Agent 已绑定的所有关系，并重新写入最新的多对多映射记录，具有事务强一致性。</p>
     *
     * @param agentId 智能体唯一 ID，非空
     * @param kbIds   最新的绑定知识库 ID 列表，可为空（为空时表示全部清理解绑）
     * @return 无
     */
    void replaceKbsForAgent(String agentId, List<String> kbIds);

    /**
     * 获取指定 Agent 已绑定授权的所有知识库唯一 ID 列表。
     *
     * @param agentId 智能体 ID，非空
     * @return 绑定的知识库 ID 列表；未绑定时返回空列表
     */
    List<String> getKbIdsByAgentId(String agentId);

    /**
     * 级联清除指定 Agent 的所有绑定映射关系。
     *
     * @param agentId 智能体唯一 ID，非空
     * @return 无
     */
    void deleteBindsByAgentId(String agentId);

    /**
     * 级联清除与指定知识库绑定的所有 Agent 映射关系。
     *
     * @param kbId 知识库唯一 ID，非空
     * @return 无
     */
    void deleteBindsByKbId(String kbId);
}
