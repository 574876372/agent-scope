package com.cl.agent.dto.rag;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次检索的完整过程记录。
 * <p>对话中作为 SSE {@code retrieval} 事件推送并写入助手消息（此时只保留来源段，去掉各阶段中间结果以控制体积）；
 * 知识库演练场返回完整记录，分步展示：改写检索词 → 向量命中 → 关键词命中 → 融合结果 → 扩展后最终上下文。</p>
 */
@Data
public class RetrievalTrace implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户原始问题 */
    private String query;

    /** 实际用于检索的问题；未改写时与 query 相同 */
    private String rewrittenQuery;

    /** 是否执行了查询改写（开关开启且存在对话历史） */
    private boolean rewriteApplied;

    /** 向量召回结果（各知识库合并，按相似度降序） */
    private List<RetrievalHit> vectorHits = new ArrayList<>();

    /** 关键词召回结果（按全文索引相关度降序） */
    private List<RetrievalHit> keywordHits = new ArrayList<>();

    /** RRF 融合（及重排）后的候选，按最终排名升序 */
    private List<RetrievalHit> fusedHits = new ArrayList<>();

    /** 是否执行了重排序 */
    private boolean reranked;

    /** 重排模型名称；未重排时为 null */
    private String rerankModel;

    /** 上下文扩展、合并后的最终来源段，按引用编号排列 */
    private List<RetrievalSegment> segments = new ArrayList<>();

    /** 注入模型的完整上下文文本；对话事件中不携带（为 null） */
    private String contextText;

    /** 检索过程中的告警，如某知识库因缺少 API Key 被跳过 */
    private List<String> warnings = new ArrayList<>();

    /** 各阶段耗时（毫秒），键为 rewrite / vector / keyword / fusion / rerank / expand */
    private Map<String, Long> stageCostMs = new LinkedHashMap<>();

    /** 总耗时（毫秒） */
    private long costMs;
}
