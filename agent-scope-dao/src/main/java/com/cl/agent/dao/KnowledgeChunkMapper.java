package com.cl.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cl.agent.model.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库文档文本段落切片表数据访问 Mapper 接口。
 * <p>继承自 MyBatis Plus 的 {@link BaseMapper}，提供对 {@code t_knowledge_chunk} 表的通用 CRUD 持久化支持。</p>
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {
}
