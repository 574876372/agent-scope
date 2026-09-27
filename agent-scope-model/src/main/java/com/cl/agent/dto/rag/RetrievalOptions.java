package com.cl.agent.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索流水线的一次调用参数。
 * <p>由 GENERIC 对话前置检索、AGENTIC 检索工具与知识库演练场分别组装后传入同一条流水线，
 * 保证三处检索逻辑完全一致。数值参数为 null 时由流水线回落到 {@code agent.rag.retrieval.*} 全局默认。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalOptions {

    /** 参与检索的知识库 ID，非空 */
    @Builder.Default
    private List<String> kbIds = new ArrayList<>();

    /** 用户本轮问题，非空 */
    private String query;

    /**
     * 本轮之前的对话，按时间升序，元素为 {role, content}（role 取 user / assistant）；
     * 仅查询改写使用，可为空列表。
     */
    @Builder.Default
    private List<String[]> history = new ArrayList<>();

    /** 最终段数 Top-K；null 取全局默认 */
    private Integer finalTopK;

    /** 注入上下文总字符数上限；null 取全局默认 */
    private Integer contextMaxChars;

    /** 向量预过滤阈值；null 取全局默认 */
    private Double scoreThreshold;

    /** 是否启用查询改写；null 取全局默认 */
    private Boolean queryRewrite;

    /** 查询改写使用的对话模型厂商编码；为空时使用默认对话模型 */
    private String chatProviderCode;

    /** 查询改写使用的对话模型名称；为空时使用默认对话模型 */
    private String chatModelName;

    /** 重排模型 ID；为空时取默认重排模型，未配置则不重排 */
    private String rerankModelId;

    /** 是否在结果中保留各阶段中间结果（演练场为 true，对话为 false 以减小体积） */
    private boolean keepStages;
}
