package com.cl.agent.dto.rag;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库演练场检索请求。
 * <p>演练场与智能体调用同一条检索流水线；数值参数为空时使用全局默认，与未单独配置的智能体效果一致。</p>
 */
@Data
public class RetrievalTestRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 目标知识库 ID，必填 */
    private String kbId;

    /** 测试问题，必填 */
    private String query;

    /**
     * 模拟的前几轮用户提问（按时间升序），选填；非空且开启查询改写时，演示追问改写效果，
     * 如先问「代发接口的请求参数」，再测「那响应参数呢？」。
     */
    private List<String> previousQuestions = new ArrayList<>();

    /** 最终段数 Top-K，选填 */
    private Integer finalTopK;

    /** 注入上下文总字符数上限，选填 */
    private Integer contextMaxChars;

    /** 向量预过滤阈值，选填 */
    private Double scoreThreshold;

    /** 是否启用查询改写，选填；为空时使用全局默认 */
    private Boolean queryRewrite;

    /** 重排模型 ID，选填；为空时使用默认重排模型 */
    private String rerankModelId;
}
