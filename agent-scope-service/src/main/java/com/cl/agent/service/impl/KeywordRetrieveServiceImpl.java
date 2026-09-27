package com.cl.agent.service.impl;

import com.cl.agent.dao.KnowledgeChunkMapper;
import com.cl.agent.model.KnowledgeChunk;
import com.cl.agent.service.IKeywordRetrieveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库关键词召回服务实现。
 * <p>两步召回：先对问题中的标识符类精确词做 LIKE 包含匹配，再用 ngram 全文索引按相关度补足。
 * 全文检索失败（如索引尚未创建）时记录告警并只返回精确词结果，不影响向量召回。</p>
 */
@Service
@Slf4j
public class KeywordRetrieveServiceImpl implements IKeywordRetrieveService {

    /** 标识符类精确词：字母开头或含数字的字母数字下划线串，长度 4~64，如字段名、错误码 */
    private static final Pattern EXACT_TERM = Pattern.compile("[A-Za-z0-9_]{4,64}");

    /** 参与精确匹配的精确词数量上限，避免问题很长时产生过多 LIKE 查询 */
    private static final int MAX_EXACT_TERMS = 3;

    /** 精确词命中的基础得分，保证排在全文检索结果之前 */
    private static final double EXACT_BASE_SCORE = 1000.0;

    /** 全文检索的检索文本最大长度；过长的问题截断，避免 ngram 拆出过多词元 */
    private static final int MAX_QUERY_LENGTH = 200;

    @Autowired
    private KnowledgeChunkMapper knowledgeChunkMapper;

    /** {@inheritDoc} */
    @Override
    public List<KnowledgeChunk> search(Collection<String> kbIds, String query, int limit) {
        if (kbIds == null || kbIds.isEmpty() || query == null || query.isBlank() || limit <= 0) {
            return new ArrayList<>();
        }
        // 以切片 ID 去重并保持插入顺序：精确词命中在前，全文检索命中在后
        Map<String, KnowledgeChunk> merged = new LinkedHashMap<>();

        // 1. 精确词：同一切片命中的精确词越多得分越高
        List<String> terms = extractExactTerms(query);
        Map<String, Integer> termHits = new LinkedHashMap<>();
        for (String term : terms) {
            for (KnowledgeChunk c : knowledgeChunkMapper.searchExactTerm(kbIds, toLikePattern(term), limit)) {
                merged.putIfAbsent(c.getId(), c);
                termHits.merge(c.getId(), 1, Integer::sum);
            }
        }
        termHits.forEach((id, hits) -> merged.get(id).setKeywordScore(EXACT_BASE_SCORE + hits));

        // 2. 全文索引：补足剩余名额
        String text = query.length() > MAX_QUERY_LENGTH ? query.substring(0, MAX_QUERY_LENGTH) : query;
        try {
            for (KnowledgeChunk c : knowledgeChunkMapper.searchFullText(kbIds, text, limit)) {
                KnowledgeChunk existing = merged.putIfAbsent(c.getId(), c);
                if (existing != null && existing.getKeywordScore() != null && c.getKeywordScore() != null) {
                    // 精确词命中同时也被全文命中，附加全文得分用于同分排序
                    existing.setKeywordScore(existing.getKeywordScore() + c.getKeywordScore() / 1000.0);
                }
            }
        } catch (Exception e) {
            log.warn("[RAG-Keyword] 全文检索失败，仅使用精确词结果（请确认已执行 2026-09-27_rag_enhancement.sql 创建 ft_content 索引）: {}",
                    e.getMessage());
        }

        List<KnowledgeChunk> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(KnowledgeChunk::getKeywordScore,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (result.size() > limit) {
            result = new ArrayList<>(result.subList(0, limit));
        }
        log.debug("[RAG-Keyword] 关键词召回完成: kbIds={}, 精确词={}, 命中={}", kbIds, terms, result.size());
        return result;
    }

    /**
     * 提取问题中的标识符类精确词：必须同时含字母和数字，或含大写字母 / 下划线（驼峰字段名、常量名），
     * 纯小写英文单词（如 "what"）不算。
     *
     * @param query 用户问题
     * @return 精确词，去重后最多 {@link #MAX_EXACT_TERMS} 个，按长度降序（越长越具体）
     */
    static List<String> extractExactTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher m = EXACT_TERM.matcher(query);
        while (m.find()) {
            String t = m.group();
            boolean hasDigit = t.chars().anyMatch(Character::isDigit);
            boolean hasLetter = t.chars().anyMatch(Character::isLetter);
            boolean hasUpperOrUnderscore = t.chars().anyMatch(c -> Character.isUpperCase(c) || c == '_');
            if ((hasDigit && hasLetter) || (hasLetter && hasUpperOrUnderscore)) {
                terms.add(t);
            }
        }
        List<String> list = new ArrayList<>(terms);
        list.sort(Comparator.comparingInt(String::length).reversed());
        return list.size() > MAX_EXACT_TERMS ? list.subList(0, MAX_EXACT_TERMS) : list;
    }

    /**
     * 转义 LIKE 通配符并包成包含匹配模式。
     *
     * @param term 精确词
     * @return 如 {@code %partner\_no%}
     */
    private static String toLikePattern(String term) {
        String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
