package com.cl.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cl.agent.dao.AgentKbRelMapper;
import com.cl.agent.dao.KnowledgeBaseMapper;
import com.cl.agent.dao.KnowledgeChunkMapper;
import com.cl.agent.dao.KnowledgeDocumentMapper;
import com.cl.agent.model.AgentKbRel;
import com.cl.agent.model.KnowledgeBase;
import com.cl.agent.model.KnowledgeChunk;
import com.cl.agent.model.KnowledgeDocument;
import com.cl.agent.service.IKnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库数据服务实现类。
 * <p>继承自 {@link IKnowledgeService}，负责使用 MyBatis Plus 映射器进行数据库增删改查。
 * 涉及多表操作（如级联清除绑定、物理删除文件记录时删除切片等）会开启 {@link Transactional} 保证事务完整度。</p>
 */
@Service
@Slf4j
public class KnowledgeServiceImpl implements IKnowledgeService {

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Autowired
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Autowired
    private AgentKbRelMapper agentKbRelMapper;

    // ========================================================
    // 1. 知识库主表 (t_knowledge_base) CRUD
    // ========================================================

    /**
     * 保存或更新知识库基础元数据实体。
     * <p>使用说明：当数据库中已存在同主键 ID 的记录时更新其内容，否则执行全新插入插入。</p>
     *
     * @param kb 待保存的知识库信息实体，非空
     * @return 无
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveBase(KnowledgeBase kb) {
        log.debug("[Service-KB] 保存知识库: id={}, name={}", kb.getId(), kb.getName());
        if (knowledgeBaseMapper.selectById(kb.getId()) != null) {
            knowledgeBaseMapper.updateById(kb);
        } else {
            knowledgeBaseMapper.insert(kb);
        }
    }

    /**
     * 根据主键 ID 获取对应的知识库实体。
     *
     * @param id 知识库唯一标识 ID，非空
     * @return {@link KnowledgeBase} 知识库实体；不存在时返回 null
     */
    @Override
    public KnowledgeBase getBaseById(String id) {
        log.debug("[Service-KB] 根据主键获取知识库: id={}", id);
        return knowledgeBaseMapper.selectById(id);
    }

    /**
     * 获取指定创建用户下的所有活跃知识库列表。
     *
     * @param userId 创建拥有者用户 ID，非空
     * @return {@link KnowledgeBase} 列表；未查询到时返回空列表
     */
    @Override
    public List<KnowledgeBase> listBasesByUserId(String userId) {
        log.debug("[Service-KB] 列出创建用户名下的知识库: userId={}", userId);
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getUserId, userId);
        List<KnowledgeBase> list = knowledgeBaseMapper.selectList(wrapper);
        return list != null ? list : List.of();
    }

    /**
     * 获取系统内所有活跃的知识库列表。
     *
     * @return {@link KnowledgeBase} 列表；为空时返回空列表
     */
    @Override
    public List<KnowledgeBase> listAllBases() {
        log.debug("[Service-KB] 获取全系统活跃知识库列表");
        List<KnowledgeBase> list = knowledgeBaseMapper.selectList(null);
        return list != null ? list : List.of();
    }

    /**
     * 根据主键 ID 逻辑删除对应的知识库实体。
     *
     * @param id 待删除知识库的 ID，非空
     * @return 无
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBaseById(String id) {
        log.debug("[Service-KB] 逻辑删除知识库: id={}", id);
        knowledgeBaseMapper.deleteById(id);
    }

    // ========================================================
    // 2. 知识库文档明细表 (t_knowledge_document) CRUD
    // ========================================================

    /**
     * 保存或更新上传的文档元数据明细记录。
     *
     * @param doc 待保存的文档元数据明细，非空
     * @return 无
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveDocument(KnowledgeDocument doc) {
        log.debug("[Service-Doc] 保存文档记录: id={}, name={}, status={}", doc.getId(), doc.getName(), doc.getStatus());
        if (doc.getErrorMessage() != null && doc.getErrorMessage().length() > 500) {
            doc.setErrorMessage(doc.getErrorMessage().substring(0, 497) + "...");
        }
        if (knowledgeDocumentMapper.selectById(doc.getId()) != null) {
            knowledgeDocumentMapper.updateById(doc);
        } else {
            knowledgeDocumentMapper.insert(doc);
        }
    }

    /**
     * 根据主键 ID 获取对应的文档明细元数据。
     *
     * @param id 文档唯一标识 ID，非空
     * @return {@link KnowledgeDocument} 文档实体；不存在时返回 null
     */
    @Override
    public KnowledgeDocument getDocumentById(String id) {
        log.debug("[Service-Doc] 根据主键获取文档记录: id={}", id);
        return knowledgeDocumentMapper.selectById(id);
    }

    /**
     * 获取指定知识库下关联的所有文档明细列表（包含上传中/解析中/已入库等状态）。
     *
     * @param kbId 知识库 ID，非空
     * @return {@link KnowledgeDocument} 列表；未查询到时返回空列表
     */
    @Override
    public List<KnowledgeDocument> listDocumentsByKbId(String kbId) {
        log.debug("[Service-Doc] 获取知识库关联的所有文档: kbId={}", kbId);
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getKbId, kbId);
        List<KnowledgeDocument> list = knowledgeDocumentMapper.selectList(wrapper);
        return list != null ? list : List.of();
    }

    /**
     * 根据主键 ID 逻辑删除对应的文档元数据。
     *
     * @param id 待删除文档的 ID，非空
     * @return 无
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocumentById(String id) {
        log.debug("[Service-Doc] 逻辑删除文档元数据: id={}", id);
        knowledgeDocumentMapper.deleteById(id);
    }

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
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveChunksBatch(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        log.debug("[Service-Chunk] 批量保存文档文本段切片，大小={}", chunks.size());
        for (KnowledgeChunk c : chunks) {
            if (knowledgeChunkMapper.selectById(c.getId()) != null) {
                knowledgeChunkMapper.updateById(c);
            } else {
                knowledgeChunkMapper.insert(c);
            }
        }
    }

    /**
     * 获取指定上传文档下已存储的所有纯文本切片记录。
     *
     * @param docId 文档唯一 ID，非空
     * @return {@link KnowledgeChunk} 列表；未查询到时返回空列表
     */
    @Override
    public List<KnowledgeChunk> listChunksByDocId(String docId) {
        log.debug("[Service-Chunk] 获取文档下所有文本段切片: docId={}", docId);
        LambdaQueryWrapper<KnowledgeChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeChunk::getDocId, docId)
               .orderByAsc(KnowledgeChunk::getChunkIndex);
        List<KnowledgeChunk> list = knowledgeChunkMapper.selectList(wrapper);
        return list != null ? list : List.of();
    }

    /**
     * 获取指定知识库下关联的所有切片信息列表（用于大批审计或本地检索对比）。
     *
     * @param kbId 知识库唯一 ID，非空
     * @return {@link KnowledgeChunk} 列表；未查询到时返回空列表
     */
    @Override
    public List<KnowledgeChunk> listChunksByKbId(String kbId) {
        log.debug("[Service-Chunk] 获取整个知识库下的切片数据: kbId={}", kbId);
        LambdaQueryWrapper<KnowledgeChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeChunk::getKbId, kbId);
        List<KnowledgeChunk> list = knowledgeChunkMapper.selectList(wrapper);
        return list != null ? list : List.of();
    }

    /**
     * 级联删除指定文档下的所有文本切片实体。
     *
     * @param docId 文档唯一 ID，非空
     * @return 无
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteChunksByDocId(String docId) {
        log.debug("[Service-Chunk] 级联物理删除文档对应的文本切片: docId={}", docId);
        LambdaQueryWrapper<KnowledgeChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeChunk::getDocId, docId);
        knowledgeChunkMapper.delete(wrapper);
    }

    /**
     * 级联删除指定知识库下的所有文本切片实体。
     *
     * @param kbId 知识库唯一 ID，非空
     * @return 无
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteChunksByKbId(String kbId) {
        log.debug("[Service-Chunk] 级联物理删除知识库下的所有文本切片: kbId={}", kbId);
        LambdaQueryWrapper<KnowledgeChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeChunk::getKbId, kbId);
        knowledgeChunkMapper.delete(wrapper);
    }

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
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceKbsForAgent(String agentId, List<String> kbIds) {
        log.info("[Service-Rel] 开始替换 Agent 与知识库关联: agentId={}, kbIds={}", agentId, kbIds);
        // 1. 首先级联物理清理解绑该 Agent 原本映射的所有旧关联
        deleteBindsByAgentId(agentId);

        // 2. 写入最新的绑定映射记录，并为其分配唯一主键 ID
        if (kbIds != null && !kbIds.isEmpty()) {
            for (String kbId : kbIds) {
                AgentKbRel rel = AgentKbRel.builder()
                        .id(UUID.randomUUID().toString())
                        .agentId(agentId)
                        .kbId(kbId)
                        .build();
                agentKbRelMapper.insert(rel);
            }
            log.info("[Service-Rel] 替换 Agent 关联知识库完成，绑定数量={}", kbIds.size());
        }
    }

    /**
     * 获取指定 Agent 已绑定授权的所有知识库唯一 ID 列表。
     *
     * @param agentId 智能体 ID，非空
     * @return 绑定的知识库 ID 列表；未绑定时返回空列表
     */
    @Override
    public List<String> getKbIdsByAgentId(String agentId) {
        log.debug("[Service-Rel] 获取 Agent 关联的知识库 ID 列表: agentId={}", agentId);
        LambdaQueryWrapper<AgentKbRel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentKbRel::getAgentId, agentId);
        List<AgentKbRel> list = agentKbRelMapper.selectList(wrapper);
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        return list.stream().map(AgentKbRel::getKbId).collect(Collectors.toList());
    }

    /**
     * 级联清除指定 Agent 的所有绑定映射关系。
     *
     * @param agentId 智能体唯一 ID，非空
     * @return 无
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBindsByAgentId(String agentId) {
        log.debug("[Service-Rel] 物理清除 Agent 的多对多映射关系: agentId={}", agentId);
        LambdaQueryWrapper<AgentKbRel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentKbRel::getAgentId, agentId);
        agentKbRelMapper.delete(wrapper);
    }

    /**
     * 级联清除与指定知识库绑定的所有 Agent 映射关系。
     *
     * @param kbId 知识库唯一 ID，非空
     * @return 无
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBindsByKbId(String kbId) {
        log.debug("[Service-Rel] 物理清除与指定知识库关联的所有绑定关系: kbId={}", kbId);
        LambdaQueryWrapper<AgentKbRel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentKbRel::getKbId, kbId);
        agentKbRelMapper.delete(wrapper);
    }
}
