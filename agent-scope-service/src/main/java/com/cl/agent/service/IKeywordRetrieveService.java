package com.cl.agent.service;

import com.cl.agent.model.KnowledgeChunk;

import java.util.Collection;
import java.util.List;

/**
 * 知识库关键词召回服务（方案中的 KeywordRetriever）。
 * <p>基于切片表 {@code content} 的 ngram 全文索引做关键词检索，与向量库类型无关；
 * 用于补足向量检索对字段名、错误码、条款号等精确词不敏感的问题。</p>
 */
public interface IKeywordRetrieveService {

    /**
     * 在指定知识库范围内做关键词召回。
     * <p>使用说明：由检索流水线与向量召回并行调用。问题中形如标识符的词（字母数字下划线组成、长度不少于 4，
     * 如 {@code partnerOutBizNo}、{@code BBLMAP00000}）先做精确包含匹配并排在最前，其余名额按全文索引相关度补足。</p>
     *
     * @param kbIds 知识库 ID 集合，为空时直接返回空列表
     * @param query 检索文本，为空时直接返回空列表
     * @param limit 返回条数上限，非正数时返回空列表
     * @return 命中切片，按相关度降序，{@code keywordScore} 已赋值（精确词命中为 1000 + 命中词数）；无命中时返回空列表
     */
    List<KnowledgeChunk> search(Collection<String> kbIds, String query, int limit);
}
