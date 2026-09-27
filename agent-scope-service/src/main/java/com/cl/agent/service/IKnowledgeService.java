package com.cl.agent.service;

import com.cl.agent.model.AgentKbRel;
import com.cl.agent.model.KnowledgeBase;
import com.cl.agent.model.KnowledgeChunk;
import com.cl.agent.model.KnowledgeDocument;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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
     * 按「文档 ID → 切片序号集合」批量查询切片。
     * <p>使用说明：检索流水线把向量召回结果（向量库只保存文档 ID 与序号）还原为 MySQL 切片，以取得章节路径等字段。</p>
     *
     * @param docIndexes 文档 ID 到切片序号集合的映射，为空时返回空列表
     * @return 命中的切片，顺序不保证；向量库中存在而切片表缺失的不返回
     */
    List<KnowledgeChunk> listChunksByDocIndexes(Map<String, ? extends Collection<Integer>> docIndexes);

    /**
     * 查询文档内序号在 [fromIndex, toIndex] 区间的切片。
     * <p>使用说明：上下文扩展时补充命中切片的相邻切片。</p>
     *
     * @param docId     文档 ID，非空
     * @param fromIndex 起始序号（含），小于 0 时按 0 处理
     * @param toIndex   结束序号（含）
     * @return 区间内切片，按序号升序；无数据时返回空列表
     */
    List<KnowledgeChunk> listChunksInRange(String docId, int fromIndex, int toIndex);

    /**
     * 查询文档内章节路径等于指定值的全部切片。
     * <p>使用说明：技术文档类型整章带入时调用。</p>
     *
     * @param docId       文档 ID，非空
     * @param sectionPath 章节路径，非空
     * @return 该章节的切片，按序号升序；无数据时返回空列表
     */
    List<KnowledgeChunk> listChunksBySection(String docId, String sectionPath);

    /**
     * 按 ID 批量查询文档元数据。
     *
     * @param ids 文档 ID 集合，为空时返回空列表
     * @return 文档列表；已删除或不存在的 ID 不返回
     */
    List<KnowledgeDocument> listDocumentsByIds(Collection<String> ids);

    /**
     * 按 ID 批量查询知识库。
     *
     * @param ids 知识库 ID 集合，为空时返回空列表
     * @return 知识库列表；已删除或不存在的 ID 不返回
     */
    List<KnowledgeBase> listBasesByIds(Collection<String> ids);

    /**
     * 级联删除指定文档下的所有文本切片实体。
     *
     * @param docId 文档唯一 ID，非空
     * @return 无
     */
    void deleteChunksByDocId(String docId);

    /**
     * 物理删除文档的全部切片（含已逻辑删除的行）。
     * <p>使用说明：重新解析前调用。切片主键由内容派生，逻辑删除后重新入库会主键冲突，因此必须物理删除；
     * 须在清理向量之后调用（清理向量需要按切片 ID 定位）。</p>
     *
     * @param docId 文档 ID，非空
     * @return 删除的行数
     */
    int purgeChunksByDocId(String docId);

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
     * 获取绑定了指定知识库的全部 Agent ID。
     * <p>使用说明：知识库删除或配置变更前调用，据此让这些 Agent 的运行时缓存失效；须在解除绑定之前调用。</p>
     *
     * @param kbId 知识库 ID，非空
     * @return 绑定该知识库的 Agent ID 列表；无绑定时返回空列表
     */
    List<String> getAgentIdsByKbId(String kbId);

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
