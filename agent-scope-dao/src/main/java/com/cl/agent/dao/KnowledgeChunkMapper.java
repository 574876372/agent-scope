package com.cl.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cl.agent.model.KnowledgeChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 知识库切片表 {@code t_knowledge_chunk} 数据访问接口。
 * <p>除 MyBatis-Plus 通用 CRUD 外，提供关键词召回所需的全文检索与精确词匹配查询；
 * 全文检索依赖 {@code ft_content} ngram 全文索引（见 docx/sql/2026-09-27_rag_enhancement.sql）。</p>
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * 在指定知识库范围内做 ngram 全文检索（自然语言模式），按相关度降序返回。
     *
     * @param kbIds 知识库 ID 集合，非空
     * @param query 检索文本，非空；自然语言模式下不解析布尔运算符，无需转义
     * @param limit 返回条数上限，正数
     * @return 命中切片，{@code keywordScore} 为全文索引相关度；无命中时返回空列表
     */
    @Select("<script>"
            + "SELECT id, doc_id, kb_id, content, chunk_index, token_count, section_path, chunk_type, "
            + "MATCH(content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS keyword_score "
            + "FROM t_knowledge_chunk "
            + "WHERE del_flag = 0 AND kb_id IN "
            + "<foreach collection='kbIds' item='kbId' open='(' separator=',' close=')'>#{kbId}</foreach> "
            + "AND MATCH(content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) "
            + "ORDER BY keyword_score DESC LIMIT #{limit}"
            + "</script>")
    List<KnowledgeChunk> searchFullText(@Param("kbIds") Collection<String> kbIds,
                                        @Param("query") String query,
                                        @Param("limit") int limit);

    /**
     * 在指定知识库范围内查找原文包含某个精确词（字段名、错误码等）的切片。
     *
     * @param kbIds       知识库 ID 集合，非空
     * @param likePattern 已转义 % _ \ 并前后加 % 的 LIKE 模式，如 {@code %partnerOutBizNo%}
     * @param limit       返回条数上限，正数
     * @return 命中切片（{@code keywordScore} 为空，由调用方赋值），按文档与序号排序；无命中时返回空列表
     */
    @Select("<script>"
            + "SELECT id, doc_id, kb_id, content, chunk_index, token_count, section_path, chunk_type "
            + "FROM t_knowledge_chunk "
            + "WHERE del_flag = 0 AND kb_id IN "
            + "<foreach collection='kbIds' item='kbId' open='(' separator=',' close=')'>#{kbId}</foreach> "
            + "AND content LIKE #{likePattern} "
            + "ORDER BY doc_id, chunk_index LIMIT #{limit}"
            + "</script>")
    List<KnowledgeChunk> searchExactTerm(@Param("kbIds") Collection<String> kbIds,
                                         @Param("likePattern") String likePattern,
                                         @Param("limit") int limit);

    /**
     * 物理删除文档的全部切片（含已逻辑删除的行）。
     * <p>重新解析时使用：切片主键由内容派生，逻辑删除后以相同主键重新插入会主键冲突；切片可随时由原文件重建，无需保留历史。</p>
     *
     * @param docId 文档 ID，非空
     * @return 删除的行数
     */
    @Delete("DELETE FROM t_knowledge_chunk WHERE doc_id = #{docId}")
    int purgeByDocId(@Param("docId") String docId);
}
