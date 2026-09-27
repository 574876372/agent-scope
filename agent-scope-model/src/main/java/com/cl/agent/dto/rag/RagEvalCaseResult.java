package com.cl.agent.dto.rag;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个评估用例的运行结果。
 */
@Data
public class RagEvalCaseResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用例 ID */
    private String caseId;

    /** 测试问题 */
    private String question;

    /** 要点总数 */
    private int pointCount;

    /** 召回率：检索上下文覆盖的要点数 / 要点总数（0 ~ 1） */
    private double contextRecall;

    /** 答案完整度：模型回答覆盖的要点数 / 要点总数（0 ~ 1）；未生成回答时为 null */
    private Double answerCompleteness;

    /** 检索上下文中缺失的要点 */
    private List<String> missingInContext = new ArrayList<>();

    /** 模型回答中缺失的要点；未生成回答时为空列表 */
    private List<String> missingInAnswer = new ArrayList<>();

    /** 最终来源段数 */
    private int segmentCount;

    /** 检索上下文总字符数 */
    private int contextChars;

    /** 模型回答；未生成回答或生成失败时为 null */
    private String answer;

    /** 检索与生成过程中的告警或错误 */
    private List<String> warnings = new ArrayList<>();

    /** 本用例耗时（毫秒） */
    private long costMs;
}
