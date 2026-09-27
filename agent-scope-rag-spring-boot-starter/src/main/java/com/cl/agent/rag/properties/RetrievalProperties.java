package com.cl.agent.rag.properties;

import lombok.Data;

/**
 * 检索流水线全局默认参数，对应 {@code agent.rag.retrieval.*}。
 * <p>智能体级配置（最终段数、上下文总长、预过滤阈值、查询改写、重排模型）为空时使用这里的值；
 * 每路召回数等与内容无关的参数只在全局配置。</p>
 */
@Data
public class RetrievalProperties {

    /** 每个知识库向量召回的切片数 */
    private int vectorTopN = 20;

    /** 关键词（全文索引）召回的切片数，跨所有参与检索的知识库合计 */
    private int keywordTopN = 20;

    /** RRF 融合常数 k：得分 = Σ 1 / (k + 排名)；越大越弱化排名差异 */
    private int rrfK = 60;

    /** 最终交给模型的段数（上下文扩展、合并后的连续原文段） */
    private int finalTopK = 5;

    /** 注入上下文的总字符数上限，超出时按融合排名从低到高舍弃 */
    private int contextMaxChars = 8000;

    /** 技术文档类型整章带入时，单个章节的最大字符数；超过则改为按相邻切片扩展 */
    private int sectionMaxChars = 4000;

    /** 是否默认开启查询改写（结合对话历史把追问改写为独立检索词） */
    private boolean queryRewrite = true;

    /** 查询改写参考的最近对话轮数 */
    private int historyTurns = 3;

    /** 进入重排的融合候选数上限 */
    private int rerankCandidates = 30;
}
