package com.cl.agent.biz.rag;

import com.cl.agent.dto.model.ModelConnection;
import com.cl.agent.dto.rag.RetrievalHit;
import com.cl.agent.dto.rag.RetrievalOptions;
import com.cl.agent.dto.rag.RetrievalSegment;
import com.cl.agent.dto.rag.RetrievalTrace;
import com.cl.agent.model.AgentInfo;
import com.cl.agent.model.KnowledgeBase;
import com.cl.agent.model.KnowledgeChunk;
import com.cl.agent.model.KnowledgeDocument;
import com.cl.agent.rag.properties.RetrievalProperties;
import com.cl.agent.service.IKeywordRetrieveService;
import com.cl.agent.service.IKnowledgeService;
import com.cl.agent.service.IModelConfigService;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库检索流水线：GENERIC 对话前置检索、AGENTIC 检索工具与知识库演练场共用的唯一检索实现。
 * <p>步骤（方案第三、六节）：</p>
 * <ol>
 *   <li>查询改写（可选）：结合最近几轮对话改写为独立检索词；</li>
 *   <li>两路召回：每个知识库用自己绑定的向量模型做向量检索（阈值仅作预过滤），另做一路切片表全文索引关键词检索；</li>
 *   <li>RRF 融合：每个知识库的向量结果与关键词结果各算一路，得分 = Σ 1 / (k + 排名)，不依赖各路原始分数；</li>
 *   <li>重排序（可选）：配置了重排模型时对融合候选重排；</li>
 *   <li>上下文扩展：按知识库类型补相邻切片或整章，合并为连续原文，受段数与总长上限约束；</li>
 *   <li>组装：每段标注「来源：文档名 › 章节 › 切片 #a-#b」。</li>
 * </ol>
 * <p>异常隔离：单个知识库检索失败（模型停用、缺 API Key、向量库不可用）时记录告警并跳过，其余知识库照常返回；
 * 查询改写、关键词检索、重排失败均降级处理，不中断检索。</p>
 */
@Slf4j
@Component
public class RetrievalPipeline {

    /** 注入上下文的开头说明，约束模型依据资料作答并标注来源编号 */
    private static final String CONTEXT_HEADER = "以下是从知识库检索到的参考资料（共 %d 段）。回答时优先依据这些资料，"
            + "引用时在句末标注来源编号，如 [1]；资料中没有的信息请明确说明未检索到，不要编造。\n";

    @Autowired
    private IKnowledgeService knowledgeService;

    @Autowired
    private IKeywordRetrieveService keywordRetrieveService;

    @Autowired
    private IModelConfigService modelConfigService;

    @Autowired
    private RagKnowledgeProvider ragKnowledgeProvider;

    @Autowired
    private KbConfigResolver kbConfigResolver;

    @Autowired
    private QueryRewriter queryRewriter;

    @Autowired
    private Reranker reranker;

    @Autowired
    private ContextExpander contextExpander;

    /**
     * 执行一次完整检索。
     * <p>使用说明：同步阻塞调用（内部会请求 Embedding、对话与重排模型），调用方应在可阻塞的线程中执行；
     * 不写库、不推送 SSE，结果由调用方决定如何注入与展示。</p>
     *
     * @param options 检索参数，{@code kbIds} 与 {@code query} 必填；其余为 null 时取全局默认
     * @return 检索过程记录；{@code segments} 为最终来源段，{@code contextText} 为可直接注入模型的上下文
     *         （无结果时为 null）；{@code keepStages=false} 时各阶段中间结果列表为空
     */
    public RetrievalTrace run(RetrievalOptions options) {
        long start = System.currentTimeMillis();
        RetrievalProperties defaults = kbConfigResolver.retrieval();
        RetrievalTrace trace = new RetrievalTrace();
        trace.setQuery(options.getQuery());
        trace.setRewrittenQuery(options.getQuery());

        Map<String, KnowledgeBase> kbMap = loadKnowledgeBases(options.getKbIds(), trace);
        if (kbMap.isEmpty() || options.getQuery() == null || options.getQuery().isBlank()) {
            trace.setCostMs(System.currentTimeMillis() - start);
            return trace;
        }

        // ① 查询改写
        long t = System.currentTimeMillis();
        String query = rewriteIfNeeded(options, defaults, trace);
        trace.getStageCostMs().put("rewrite", System.currentTimeMillis() - t);

        // ② 向量召回（每个知识库一路）
        t = System.currentTimeMillis();
        double threshold = options.getScoreThreshold() != null ? options.getScoreThreshold() : kbConfigResolver.defaultScoreThreshold();
        Map<String, List<RetrievalHit>> vectorRoutes = vectorRecall(kbMap, query, defaults.getVectorTopN(), threshold, trace);
        trace.getStageCostMs().put("vector", System.currentTimeMillis() - t);

        // ② 关键词召回（跨知识库一路）
        t = System.currentTimeMillis();
        List<RetrievalHit> keywordHits = keywordRecall(kbMap, query, defaults.getKeywordTopN(), trace);
        trace.getStageCostMs().put("keyword", System.currentTimeMillis() - t);

        // 向量结果还原为切片表记录（补章节路径等），并补全文档名；丢弃文档已删除的残留向量
        hydrate(vectorRoutes, keywordHits, kbMap);
        vectorRoutes.values().forEach(trace.getVectorHits()::addAll);
        trace.getKeywordHits().addAll(keywordHits);

        // ③ RRF 融合
        t = System.currentTimeMillis();
        List<RetrievalHit> fused = fuse(vectorRoutes, keywordHits, defaults.getRrfK());
        trace.getStageCostMs().put("fusion", System.currentTimeMillis() - t);

        // ④ 重排序（可选）
        t = System.currentTimeMillis();
        fused = rerankIfConfigured(options, query, fused, defaults.getRerankCandidates(), trace);
        trace.getStageCostMs().put("rerank", System.currentTimeMillis() - t);
        trace.setFusedHits(fused);

        // ⑤ 上下文扩展
        t = System.currentTimeMillis();
        int topK = positive(options.getFinalTopK(), defaults.getFinalTopK());
        int maxChars = positive(options.getContextMaxChars(), defaults.getContextMaxChars());
        List<RetrievalSegment> segments = contextExpander.expand(fused, kbMap, topK, maxChars, defaults.getSectionMaxChars());
        trace.setSegments(segments);
        trace.getStageCostMs().put("expand", System.currentTimeMillis() - t);

        // ⑥ 组装
        trace.setContextText(buildContextText(segments));
        if (!options.isKeepStages()) {
            trace.setVectorHits(new ArrayList<>());
            trace.setKeywordHits(new ArrayList<>());
            trace.setFusedHits(new ArrayList<>());
        }
        trace.setCostMs(System.currentTimeMillis() - start);
        log.info("[RAG-Pipeline] 检索完成: kbs={}, query={}, rewritten={}, vector={}, keyword={}, segments={}, chars={}, costMs={}",
                kbMap.keySet(), abbreviate(options.getQuery()), trace.isRewriteApplied() ? abbreviate(query) : "-",
                vectorRoutes.values().stream().mapToInt(List::size).sum(), keywordHits.size(), segments.size(),
                segments.stream().mapToInt(s -> s.getCharCount() == null ? 0 : s.getCharCount()).sum(), trace.getCostMs());
        return trace;
    }

    /**
     * 按智能体配置组装检索参数模板（不含检索词与对话历史）。
     * <p>使用说明：GENERIC 前置检索与 AGENTIC 检索器都由此取得智能体级参数，保证两种模式参数一致。</p>
     *
     * @param info  智能体配置，非空
     * @param kbIds 智能体绑定的知识库 ID，非空
     * @return 检索参数模板；智能体未配置的项为 null，由流水线取全局默认
     */
    public RetrievalOptions agentOptions(AgentInfo info, List<String> kbIds) {
        return RetrievalOptions.builder()
                .kbIds(new ArrayList<>(kbIds))
                .finalTopK(info.getRecallLimit())
                .contextMaxChars(info.getContextMaxChars())
                .scoreThreshold(info.getScoreThreshold())
                .queryRewrite(info.getQueryRewrite())
                .chatProviderCode(info.getModelType())
                .chatModelName(info.getModelName())
                .rerankModelId(isBlank(info.getRerankModelId()) ? null : info.getRerankModelId())
                .build();
    }

    /**
     * 把来源段组装为注入模型的上下文文本。
     *
     * @param segments 来源段，已分配引用编号
     * @return 以 {@code <retrieved_knowledge>} 包裹的上下文；无来源段时返回 null
     */
    public static String buildContextText(List<RetrievalSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<retrieved_knowledge>\n");
        sb.append(String.format(CONTEXT_HEADER, segments.size()));
        for (RetrievalSegment s : segments) {
            sb.append("\n[").append(s.getCitation()).append("] 来源：").append(s.sourceLabel()).append('\n');
            sb.append(s.getContent()).append('\n');
        }
        sb.append("</retrieved_knowledge>");
        return sb.toString();
    }

    // ========================================================
    // 各步骤实现
    // ========================================================

    /**
     * 加载参与检索的知识库，不存在的记录告警并跳过；返回的映射保持入参顺序。
     */
    private Map<String, KnowledgeBase> loadKnowledgeBases(List<String> kbIds, RetrievalTrace trace) {
        Map<String, KnowledgeBase> kbMap = new LinkedHashMap<>();
        if (kbIds == null || kbIds.isEmpty()) {
            return kbMap;
        }
        Map<String, KnowledgeBase> found = knowledgeService.listBasesByIds(kbIds).stream()
                .collect(Collectors.toMap(KnowledgeBase::getId, kb -> kb, (a, b) -> a));
        for (String id : kbIds) {
            KnowledgeBase kb = found.get(id);
            if (kb == null) {
                trace.getWarnings().add("知识库不存在或已删除，已跳过: " + id);
            } else {
                kbMap.put(id, kb);
            }
        }
        return kbMap;
    }

    /**
     * ① 开关开启且有对话历史时改写问题；失败时沿用原问题并记录告警。
     *
     * @return 实际用于检索的问题
     */
    private String rewriteIfNeeded(RetrievalOptions options, RetrievalProperties defaults, RetrievalTrace trace) {
        boolean enabled = options.getQueryRewrite() != null ? options.getQueryRewrite() : defaults.isQueryRewrite();
        List<String[]> history = options.getHistory();
        if (!enabled || history == null || history.isEmpty()) {
            return options.getQuery();
        }
        int keep = Math.max(1, defaults.getHistoryTurns()) * 2;
        List<String[]> recent = history.size() > keep ? history.subList(history.size() - keep, history.size()) : history;
        try {
            ModelConnection conn = isBlank(options.getChatProviderCode()) || isBlank(options.getChatModelName())
                    ? modelConfigService.resolveDefaultChatConnection()
                    : modelConfigService.resolveChatConnection(options.getChatProviderCode(), options.getChatModelName());
            String rewritten = queryRewriter.rewrite(conn, recent, options.getQuery());
            if (rewritten != null) {
                trace.setRewrittenQuery(rewritten);
                trace.setRewriteApplied(true);
                return rewritten;
            }
            trace.getWarnings().add("查询改写未生效，已使用原问题检索");
        } catch (Exception e) {
            trace.getWarnings().add("查询改写失败，已使用原问题检索: " + e.getMessage());
        }
        return options.getQuery();
    }

    /**
     * ② 向量召回：每个知识库一路；同一向量模型的问题向量只计算一次。单库失败记录告警并跳过。
     *
     * @return 知识库 ID 到其向量命中列表（按相似度降序、已编排名）的映射
     */
    private Map<String, List<RetrievalHit>> vectorRecall(Map<String, KnowledgeBase> kbMap, String query, int topN,
                                                          double threshold, RetrievalTrace trace) {
        Map<String, List<RetrievalHit>> routes = new LinkedHashMap<>();
        if (!ragKnowledgeProvider.isEnabled()) {
            trace.getWarnings().add("RAG 模块未启用，已跳过向量检索");
            return routes;
        }
        // 问题向量缓存：多个知识库绑定同一向量模型时只请求一次 Embedding（模型实例由工厂按 modelId 缓存复用）
        Map<EmbeddingModel, double[]> queryVectors = new IdentityHashMap<>();
        for (KnowledgeBase kb : kbMap.values()) {
            try {
                SimpleKnowledge knowledge = ragKnowledgeProvider.getKnowledge(kb.getId());
                EmbeddingModel model = knowledge.getEmbeddingModel();
                double[] vector = queryVectors.get(model);
                if (vector == null) {
                    vector = model.embed(TextBlock.builder().text(query).build()).block();
                    queryVectors.put(model, vector);
                }
                List<Document> docs = knowledge.getEmbeddingStore().search(SearchDocumentDto.builder()
                        .queryEmbedding(vector)
                        .limit(topN)
                        .scoreThreshold(threshold)
                        .build()).block();
                List<RetrievalHit> hits = new ArrayList<>();
                if (docs != null) {
                    for (Document d : docs) {
                        if (d.getScore() != null && d.getScore() < threshold) {
                            continue;
                        }
                        Integer idx = parseIndex(d.getMetadata().getChunkId());
                        if (d.getMetadata().getDocId() == null || idx == null) {
                            continue;
                        }
                        RetrievalHit h = new RetrievalHit();
                        h.setKbId(kb.getId());
                        h.setKbName(kb.getName());
                        h.setDocId(d.getMetadata().getDocId());
                        h.setChunkIndex(idx);
                        h.setContent(d.getMetadata().getContentText());
                        h.setVectorScore(d.getScore());
                        hits.add(h);
                    }
                }
                hits.sort(Comparator.comparing(RetrievalHit::getVectorScore, Comparator.nullsLast(Comparator.reverseOrder())));
                for (int i = 0; i < hits.size(); i++) {
                    hits.get(i).setVectorRank(i + 1);
                }
                routes.put(kb.getId(), hits);
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("[RAG-Pipeline] 知识库向量检索失败，已跳过: kbId={}, name={}, reason={}", kb.getId(), kb.getName(), reason);
                trace.getWarnings().add("知识库「" + kb.getName() + "」向量检索失败，已跳过: " + reason);
            }
        }
        return routes;
    }

    /**
     * ② 关键词召回：切片表全文索引 + 精确词匹配；失败时记录告警并返回空列表。
     */
    private List<RetrievalHit> keywordRecall(Map<String, KnowledgeBase> kbMap, String query, int topN, RetrievalTrace trace) {
        List<RetrievalHit> hits = new ArrayList<>();
        try {
            List<KnowledgeChunk> chunks = keywordRetrieveService.search(kbMap.keySet(), query, topN);
            for (KnowledgeChunk c : chunks) {
                RetrievalHit h = fromChunk(c, kbMap.get(c.getKbId()));
                h.setKeywordScore(c.getKeywordScore());
                h.setKeywordRank(hits.size() + 1);
                hits.add(h);
            }
        } catch (Exception e) {
            log.warn("[RAG-Pipeline] 关键词检索失败: {}", e.getMessage());
            trace.getWarnings().add("关键词检索失败，仅使用向量结果: " + e.getMessage());
        }
        return hits;
    }

    /**
     * 用切片表记录补全向量命中的切片 ID、章节路径与切片类型，并为所有命中补全文档名；
     * 文档已被删除的残留向量从结果中移除，其余路的排名随之重排。
     */
    private void hydrate(Map<String, List<RetrievalHit>> vectorRoutes, List<RetrievalHit> keywordHits,
                         Map<String, KnowledgeBase> kbMap) {
        Map<String, Set<Integer>> docIndexes = new HashMap<>();
        vectorRoutes.values().forEach(list -> list.forEach(h ->
                docIndexes.computeIfAbsent(h.getDocId(), k -> new HashSet<>()).add(h.getChunkIndex())));
        Map<String, KnowledgeChunk> chunkByKey = new HashMap<>();
        for (KnowledgeChunk c : knowledgeService.listChunksByDocIndexes(docIndexes)) {
            chunkByKey.put(c.getDocId() + "#" + c.getChunkIndex(), c);
        }

        Set<String> docIds = new HashSet<>(docIndexes.keySet());
        keywordHits.forEach(h -> docIds.add(h.getDocId()));
        Map<String, KnowledgeDocument> docs = knowledgeService.listDocumentsByIds(docIds).stream()
                .collect(Collectors.toMap(KnowledgeDocument::getId, d -> d, (a, b) -> a));

        for (Map.Entry<String, List<RetrievalHit>> route : vectorRoutes.entrySet()) {
            List<RetrievalHit> kept = new ArrayList<>();
            for (RetrievalHit h : route.getValue()) {
                KnowledgeDocument doc = docs.get(h.getDocId());
                if (doc == null) {
                    continue;
                }
                KnowledgeChunk c = chunkByKey.get(h.key());
                if (c != null) {
                    h.setChunkId(c.getId());
                    h.setSectionPath(c.getSectionPath());
                    h.setChunkType(c.getChunkType());
                    h.setContent(c.getContent());
                }
                h.setDocName(doc.getName());
                h.setVectorRank(kept.size() + 1);
                kept.add(h);
            }
            route.setValue(kept);
        }
        keywordHits.removeIf(h -> !docs.containsKey(h.getDocId()));
        for (int i = 0; i < keywordHits.size(); i++) {
            RetrievalHit h = keywordHits.get(i);
            h.setDocName(docs.get(h.getDocId()).getName());
            h.setKeywordRank(i + 1);
        }
    }

    /**
     * ③ RRF 融合：每个知识库的向量结果与关键词结果各为一路，得分 = Σ 1 / (k + 排名)。
     *
     * @return 融合后的候选，按融合得分降序；同一切片在多路出现时合并为一条并保留各路得分与排名
     */
    static List<RetrievalHit> fuse(Map<String, List<RetrievalHit>> vectorRoutes, List<RetrievalHit> keywordHits, int k) {
        Map<String, RetrievalHit> merged = new LinkedHashMap<>();
        for (List<RetrievalHit> route : vectorRoutes.values()) {
            for (RetrievalHit h : route) {
                RetrievalHit target = merged.computeIfAbsent(h.key(), key -> copyOf(h));
                target.setFusedScore(nz(target.getFusedScore()) + 1.0 / (k + h.getVectorRank()));
            }
        }
        for (RetrievalHit h : keywordHits) {
            RetrievalHit target = merged.get(h.key());
            if (target == null) {
                target = copyOf(h);
                merged.put(h.key(), target);
            } else {
                target.setKeywordScore(h.getKeywordScore());
                target.setKeywordRank(h.getKeywordRank());
                if (target.getChunkId() == null) {
                    target.setChunkId(h.getChunkId());
                    target.setSectionPath(h.getSectionPath());
                    target.setChunkType(h.getChunkType());
                    target.setContent(h.getContent());
                }
            }
            target.setFusedScore(nz(target.getFusedScore()) + 1.0 / (k + h.getKeywordRank()));
        }
        List<RetrievalHit> fused = new ArrayList<>(merged.values());
        fused.sort(Comparator.comparing(RetrievalHit::getFusedScore, Comparator.reverseOrder()));
        return fused;
    }

    /**
     * ④ 配置了重排模型时对前若干个融合候选重排；未配置或失败时保持融合顺序。
     */
    private List<RetrievalHit> rerankIfConfigured(RetrievalOptions options, String query, List<RetrievalHit> fused,
                                                  int candidates, RetrievalTrace trace) {
        if (fused.size() < 2) {
            return fused;
        }
        ModelConnection conn;
        try {
            conn = modelConfigService.resolveRerankConnection(options.getRerankModelId());
        } catch (Exception e) {
            trace.getWarnings().add("重排模型不可用，已跳过重排: " + e.getMessage());
            return fused;
        }
        if (conn == null) {
            return fused;
        }
        int n = Math.min(Math.max(candidates, 1), fused.size());
        List<RetrievalHit> head = new ArrayList<>(fused.subList(0, n));
        try {
            List<Double> scores = reranker.score(conn, query,
                    head.stream().map(h -> h.getContent() == null ? "" : h.getContent()).collect(Collectors.toList()));
            for (int i = 0; i < head.size(); i++) {
                head.get(i).setRerankScore(scores.get(i));
            }
            head.sort(Comparator.comparing(RetrievalHit::getRerankScore, Comparator.reverseOrder()));
            List<RetrievalHit> result = new ArrayList<>(head);
            result.addAll(fused.subList(n, fused.size()));
            trace.setReranked(true);
            trace.setRerankModel(conn.getModelName());
            return result;
        } catch (Exception e) {
            log.warn("[RAG-Pipeline] 重排失败，保持融合排序: model={}, reason={}", conn.getModelName(), e.getMessage());
            trace.getWarnings().add("重排失败，已保持融合排序: " + e.getMessage());
            return fused;
        }
    }

    // ========================================================
    // 工具方法
    // ========================================================

    private static RetrievalHit fromChunk(KnowledgeChunk c, KnowledgeBase kb) {
        RetrievalHit h = new RetrievalHit();
        h.setChunkId(c.getId());
        h.setKbId(c.getKbId());
        h.setKbName(kb != null ? kb.getName() : null);
        h.setDocId(c.getDocId());
        h.setChunkIndex(c.getChunkIndex());
        h.setSectionPath(c.getSectionPath());
        h.setChunkType(c.getChunkType());
        h.setContent(c.getContent());
        return h;
    }

    private static RetrievalHit copyOf(RetrievalHit h) {
        RetrievalHit c = new RetrievalHit();
        c.setChunkId(h.getChunkId());
        c.setKbId(h.getKbId());
        c.setKbName(h.getKbName());
        c.setDocId(h.getDocId());
        c.setDocName(h.getDocName());
        c.setChunkIndex(h.getChunkIndex());
        c.setSectionPath(h.getSectionPath());
        c.setChunkType(h.getChunkType());
        c.setContent(h.getContent());
        c.setVectorScore(h.getVectorScore());
        c.setVectorRank(h.getVectorRank());
        c.setKeywordScore(h.getKeywordScore());
        c.setKeywordRank(h.getKeywordRank());
        return c;
    }

    private static Integer parseIndex(String chunkId) {
        if (chunkId == null) {
            return null;
        }
        try {
            return Integer.parseInt(chunkId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private static int positive(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
