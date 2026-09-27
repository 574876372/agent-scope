package com.cl.agent.dto.rag;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次评估运行的汇总结果。
 */
@Data
public class RagEvalRunResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 运行的用例数 */
    private int total;

    /** 平均召回率（0 ~ 1） */
    private double avgContextRecall;

    /** 平均答案完整度（0 ~ 1）；未生成回答时为 null */
    private Double avgAnswerCompleteness;

    /** 召回率为 1（全部要点都被检索到）的用例数 */
    private int fullyRecalled;

    /** 总耗时（毫秒） */
    private long costMs;

    /** 各用例结果，顺序与用例列表一致 */
    private List<RagEvalCaseResult> results = new ArrayList<>();
}
